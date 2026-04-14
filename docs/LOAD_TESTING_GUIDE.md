# Load Testing Guide

This guide explains how to use the included JMeter test plan to evaluate the URL Shortener's performance under concurrent load.

---

## 1. Prerequisites

### Install Apache JMeter

```bash
# macOS (Homebrew)
brew install jmeter

# Linux (download from Apache)
wget https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.6.3.tgz
tar -xzf apache-jmeter-5.6.3.tgz
export PATH=$PATH:$(pwd)/apache-jmeter-5.6.3/bin

# Verify installation
jmeter --version
```

### Ensure the Application Is Running

```bash
docker compose up --build -d
curl http://localhost:8021/actuator/health
# Should return {"status":"UP"}
```

---

## 2. Test Plan Overview

The test plan (`jmeter/url-shortener-load-test.jmx`) contains three thread groups that simulate different traffic patterns:

### Thread Group 1: URL Creation (Write-Heavy)

| Setting | Value |
|---------|-------|
| Threads (users) | 50 |
| Loops per thread | 10 |
| Total requests | 500 |
| Ramp-up time | 10 seconds |

Simulates 50 concurrent users each creating 10 short URLs. Tests write performance and database insert throughput.

### Thread Group 2: URL Redirect (Read-Heavy)

| Setting | Value |
|---------|-------|
| Threads (users) | 100 |
| Loops per thread | 50 |
| Total requests | 5,000 |
| Ramp-up time | 15 seconds |

Each thread first creates a URL, then sends 50 redirect requests to it. Tests Redis cache performance and redirect latency.

### Thread Group 3: Mixed Workload (Realistic)

| Setting | Value |
|---------|-------|
| Threads (users) | 75 |
| Loops per thread | 20 |
| Total requests | ~1,500 |
| Read/Write ratio | 90% / 10% |
| Ramp-up time | 15 seconds |

Simulates a realistic production workload where most traffic is redirects (reads) with occasional new URL creations (writes).

---

## 3. Running the Tests

### Command Line (Recommended)

```bash
# Run the test plan and generate results
jmeter -n -t jmeter/url-shortener-load-test.jmx \
       -l jmeter/results.csv \
       -e -o jmeter/report/

# Flags:
#   -n          Non-GUI mode (required for accurate results)
#   -t          Test plan file
#   -l          Output results to CSV
#   -e -o       Generate HTML report in the specified directory
```

### GUI Mode (For Debugging)

```bash
# Open JMeter GUI and load the test plan
jmeter -t jmeter/url-shortener-load-test.jmx
```

Use GUI mode only for debugging the test plan. **Always run actual load tests in non-GUI mode** -- the GUI itself consumes significant resources and skews results.

### Cleaning Previous Results

```bash
rm -rf jmeter/results.csv jmeter/report/
```

---

## 4. Interpreting Results

### Key Metrics

| Metric | What It Means | Good Target |
|--------|---------------|-------------|
| **Average (ms)** | Mean response time | < 50ms for redirects |
| **Median (ms)** | 50th percentile response time | < 30ms for redirects |
| **90% Line (ms)** | 90th percentile (p90) | < 100ms |
| **99% Line (ms)** | 99th percentile (p99) | < 500ms |
| **Throughput** | Requests per second | > 500 rps for redirects |
| **Error %** | Percentage of failed requests | < 1% |

### What the Results Tell You

**Redirect (Cache Hit) Response Times:**
- If average < 10ms: Redis cache is working perfectly
- If average 10-50ms: Likely cache misses hitting the database
- If average > 100ms: Bottleneck somewhere (check DB connections, Redis connectivity)

**URL Creation Response Times:**
- If average < 50ms: Database writes are healthy
- If average > 200ms: Consider connection pooling tuning or database optimization

**Error Rate:**
- 0%: Everything is working
- Small % of 429s: Rate limiting is kicking in (expected behavior)
- Any 500s: Application errors that need investigation

### HTML Report

After running with `-e -o`, open `jmeter/report/index.html` in a browser. It provides:

- Response time distribution graphs
- Throughput over time
- Error breakdown
- Percentile charts

---

## 5. Tuning the Test

### Adjusting Load

Edit the thread group settings in the JMX file or via JMeter GUI:

```
To increase load:
  - Increase ThreadGroup.num_threads (more concurrent users)
  - Increase LoopController.loops (more requests per user)
  - Decrease ThreadGroup.ramp_time (users start faster)

To decrease load:
  - Reduce any of the above values
```

### Testing Rate Limiting

To trigger rate limiting, set threads to 200+ with a short ramp-up:
```
Threads: 200
Loops: 10
Ramp-up: 5 seconds
```

You should see 429 responses once any IP exceeds 100 requests/minute.

### Custom Server URL

The test plan uses variables for the target server:
- `BASE_URL`: defaults to `localhost`
- `PORT`: defaults to `8021`

To test against a remote server:
```bash
jmeter -n -t jmeter/url-shortener-load-test.jmx \
       -JBASE_URL=your-server.com \
       -JPORT=80 \
       -l jmeter/results.csv
```

---

## 6. Expected Benchmarks

These are approximate numbers for a **single EC2 t3.small instance** (2 vCPU, 2 GB RAM) running the full Docker stack:

| Operation | Avg Latency | p99 Latency | Throughput |
|-----------|-------------|-------------|------------|
| URL Creation (POST) | ~30ms | ~150ms | ~300 rps |
| Redirect - Cache Hit (GET) | ~5ms | ~30ms | ~2000 rps |
| Redirect - Cache Miss (GET) | ~20ms | ~80ms | ~800 rps |
| Stats Query (GET) | ~15ms | ~60ms | ~1000 rps |

These numbers demonstrate that:
- Redis caching provides a **4x improvement** in redirect latency
- The system can handle **thousands of concurrent redirects**
- Write operations are the bottleneck (as expected -- database writes are slower than cache reads)

### Bottleneck Analysis

If performance degrades:
1. **High DB latency**: Check PostgreSQL connection pool size (`spring.datasource.hikari.maximum-pool-size`)
2. **High Redis latency**: Check Redis memory usage and eviction policy
3. **High CPU**: The JVM or connection handling is saturated -- scale horizontally
4. **High error rate**: Check rate limiter settings and database connection limits

---

## 7. Comparison: With vs Without Redis Cache

Run the redirect test with and without Redis to quantify the caching benefit:

### With Redis (default)

```bash
jmeter -n -t jmeter/url-shortener-load-test.jmx -l results-with-cache.csv
```

### Without Redis

Temporarily disable caching by stopping Redis:
```bash
docker compose stop redis
jmeter -n -t jmeter/url-shortener-load-test.jmx -l results-no-cache.csv
docker compose start redis
```

Compare the two result files to demonstrate the concrete performance impact of caching. This is a powerful data point for interviews: "Adding Redis caching reduced average redirect latency from Xms to Yms, a Z% improvement."
