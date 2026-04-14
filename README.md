# High-Performance URL Shortener

A scalable URL shortening service built with **Java 17**, **Spring Boot 3**, **PostgreSQL**, and **Redis**, containerized with Docker and ready for AWS deployment.

## Features

- **Short Link Generation** -- Base62 encoding of database IDs for collision-free, compact URLs
- **Custom Aliases** -- Users can choose their own memorable short codes
- **Expiration Policies** -- URLs can be set to expire after a configurable duration
- **Redis Caching** -- Cache-aside pattern dramatically reduces database load on read-heavy traffic
- **IP-Based Rate Limiting** -- Prevents abuse with per-IP request throttling via Redis
- **Click Analytics** -- Track how many times each short URL has been accessed
- **Health Monitoring** -- Spring Boot Actuator endpoints for operational visibility
- **Containerized** -- Single `docker compose up` spins up the entire stack

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 15 |
| Cache | Redis 7 |
| Build Tool | Gradle 8.7 (Kotlin DSL) |
| Migration | Flyway |
| Containerization | Docker + Docker Compose |
| Deployment | AWS EC2 + Application Load Balancer |
| Load Testing | Apache JMeter |

## Quick Start

### Prerequisites

- Docker and Docker Compose installed
- (Optional) Java 17 and Gradle for local development

### Run with Docker

```bash
docker compose up --build
```

The application will be available at **`http://localhost:8021`** (host port **8021** → container **8080**; see `docker-compose.yml`). For GitHub → server hosting, see [Hosting workflow](docs/HOSTING_WORKFLOW.md).

When running **`./gradlew bootRun`** with Postgres/Redis from Compose, the JVM app still uses port **8081** from `application.yml`—use `http://localhost:8081` for those API examples.

### Run Locally (Development)

1. Start PostgreSQL and Redis:
```bash
docker compose up postgres redis
```

2. Run the application:
```bash
./gradlew bootRun
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/shorten` | Create a short URL |
| `GET` | `/{shortCode}` | Redirect to original URL |
| `GET` | `/api/v1/urls/{shortCode}/stats` | Get URL statistics |
| `DELETE` | `/api/v1/urls/{shortCode}` | Delete a short URL |
| `GET` | `/actuator/health` | Health check |

### Create a Short URL

With **Docker Compose (full stack)**, use port **8021**. With **`./gradlew bootRun`**, use **8081**.

```bash
curl -X POST http://localhost:8021/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.google.com"}'
```

Response:
```json
{
  "shortCode": "1",
  "shortUrl": "http://localhost:8021/1",
  "originalUrl": "https://www.google.com",
  "createdAt": "2026-03-23T10:00:00"
}
```

### Create with Custom Alias and Expiration

```bash
curl -X POST http://localhost:8021/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.google.com", "customAlias": "google", "expiresInMinutes": 1440}'
```

### Redirect

```bash
curl -v http://localhost:8021/google
# -> 302 redirect to https://www.google.com
```

## Project Structure

```
├── src/main/java/com/urlshortener/
│   ├── UrlShortenerApplication.java     # Entry point
│   ├── config/                          # Redis and rate limit configuration
│   ├── controller/                      # REST API endpoints
│   ├── dto/                             # Request/response objects
│   ├── entity/                          # JPA entities
│   ├── exception/                       # Custom exceptions + global handler
│   ├── filter/                          # Rate limiting servlet filter
│   ├── repository/                      # Data access layer
│   └── service/                         # Business logic + caching
├── src/main/resources/
│   ├── application.yml                  # Default configuration
│   ├── application-docker.yml           # Docker-specific overrides
│   └── db/migration/                    # Flyway SQL migrations
├── src/test/                            # Unit + integration tests
├── jmeter/                              # Load test plans
├── Dockerfile                           # Multi-stage build
├── docker-compose.yml                   # Full stack orchestration
└── docs/                                # Detailed documentation
```

## Documentation

- [Hosting workflow](docs/HOSTING_WORKFLOW.md) -- GitHub to production (VPS, Docker Compose, optional CI/CD)
- [Project Overview](docs/PROJECT_OVERVIEW.md) -- Architecture, tech stack rationale, data flow
- [API Documentation](docs/API_DOCUMENTATION.md) -- Full REST API reference with examples
- [Design Decisions](docs/DESIGN_DECISIONS.md) -- Why Base62, Redis, rate limiting, indexing
- [Deployment Guide](docs/DEPLOYMENT_GUIDE.md) -- Docker, AWS EC2, ALB setup
- [Load Testing Guide](docs/LOAD_TESTING_GUIDE.md) -- JMeter test plans and benchmarks

## Running Tests

```bash
# Unit tests
./gradlew test

# Requires Docker for Testcontainers-based integration tests
```

## Load Testing

```bash
jmeter -n -t jmeter/url-shortener-load-test.jmx -l jmeter/results.csv -e -o jmeter/report/
```

See [Load Testing Guide](docs/LOAD_TESTING_GUIDE.md) for details.

## License

This project is for educational and portfolio purposes.
