# Deployment Guide

This guide covers deploying the URL Shortener from local Docker to production on AWS EC2 behind an Application Load Balancer.

---

## 1. Local Docker Deployment

### Prerequisites

- Docker Engine 20.10+
- Docker Compose v2

### Steps

```bash
# Clone the repository
git clone <repo-url>
cd url-shortener

# Build and start all services
docker compose up --build

# Verify everything is running
docker compose ps

# Check application health
curl http://localhost:8021/actuator/health
```

### What `docker compose up` Does

1. **PostgreSQL** starts first (with healthcheck)
2. **Redis** starts in parallel (with healthcheck)
3. **Spring Boot app** waits for both to be healthy, then starts
4. Flyway runs database migrations automatically on startup
5. Application listens on port **8080** inside the container; compose maps **8021:8080** (host:container). Open **`http://localhost:8021`** on the machine running Compose.

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_USER` | `postgres` | Database username |
| `POSTGRES_PASSWORD` | `postgres` | Database password |
| `APP_BASE_URL` | `http://localhost:8021` | Base URL for generated short links (match your public URL in production) |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |

### Stopping

```bash
# Stop and remove containers
docker compose down

# Stop and remove containers + volumes (wipes data)
docker compose down -v
```

---

## 2. Building the Docker Image

### Multi-Stage Build

The Dockerfile uses a two-stage build:

**Stage 1 (Build)**: Uses `gradle:8.7-jdk17` to compile the application and produce a JAR.

**Stage 2 (Runtime)**: Uses `eclipse-temurin:17-jre-alpine` (~200MB) with only the JRE -- no build tools, no source code.

```bash
# Build the image
docker build -t url-shortener:latest .

# Check image size
docker images url-shortener
# Should be ~200-250MB
```

### Security

- Runs as a non-root user (`appuser`)
- No build tools in the runtime image
- Health check built into the container

---

## 3. AWS EC2 Deployment

### Step 3.1: Launch an EC2 Instance

1. Go to **AWS Console** -> **EC2** -> **Launch Instance**
2. Choose **Amazon Linux 2023** or **Ubuntu 22.04 LTS**
3. Instance type: **t3.small** (2 vCPU, 2 GB RAM) minimum for testing
4. Configure security group:
   - Inbound: SSH (22) from your IP, HTTP (80) from ALB security group
   - Outbound: All traffic
5. Create or select a key pair for SSH access
6. Launch the instance

### Step 3.2: Install Docker on EC2

```bash
# SSH into the instance
ssh -i your-key.pem ec2-user@<ec2-public-ip>

# Amazon Linux 2023
sudo dnf update -y
sudo dnf install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user

# Install Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# Log out and back in for group changes
exit
ssh -i your-key.pem ec2-user@<ec2-public-ip>
```

### Step 3.3: Deploy the Application

```bash
# Clone the repo (or transfer files)
git clone <repo-url>
cd url-shortener

# Set production environment variables
export APP_BASE_URL=https://your-domain.com
export POSTGRES_PASSWORD=<strong-password>

# Build and run
docker compose up --build -d

# Verify
docker compose ps
curl http://localhost:8021/actuator/health
```

### Step 3.4: Production `docker-compose.override.yml`

Create this file on the EC2 instance to override development settings:

```yaml
version: "3.9"

services:
  app:
    environment:
      APP_BASE_URL: https://your-domain.com
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    restart: always

  postgres:
    environment:
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    restart: always

  redis:
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru --requirepass ${REDIS_PASSWORD}
    restart: always
```

---

## 4. Application Load Balancer (ALB) Setup

### Why an ALB?

- **SSL termination**: HTTPS at the load balancer, HTTP to EC2 (simpler certificates)
- **Health checks**: ALB monitors the app and routes traffic only to healthy instances
- **Horizontal scaling**: Add more EC2 instances behind the same ALB
- **Security**: EC2 instances don't need public IPs

### Step 4.1: Create a Target Group

1. Go to **EC2** -> **Target Groups** -> **Create Target Group**
2. Target type: **Instances**
3. Protocol: **HTTP**, Port: **8021** (default host port from `docker-compose.yml`; container listens on **8080**)
4. Health check path: `/actuator/health`
5. Health check interval: 30 seconds
6. Healthy threshold: 2 consecutive checks
7. Register your EC2 instance

### Step 4.2: Create the ALB

1. Go to **EC2** -> **Load Balancers** -> **Create Load Balancer**
2. Choose **Application Load Balancer**
3. Scheme: **Internet-facing**
4. Listeners:
   - HTTP (80) -> redirect to HTTPS
   - HTTPS (443) -> forward to target group
5. Select your VPC and at least 2 availability zones
6. Select or create a security group:
   - Inbound: HTTP (80) and HTTPS (443) from 0.0.0.0/0
   - Outbound: port **8021** (or whatever host port you publish) to EC2 security group
7. Add your SSL certificate (from AWS Certificate Manager)
8. Set the default action to forward to your target group

### Step 4.3: Update Security Groups

EC2 security group:
- Allow inbound port **8021** from the **ALB security group** only (or the host port your target group uses)
- Remove public HTTP/HTTPS access from EC2

### Step 4.4: DNS Configuration

1. Go to **Route 53** (or your DNS provider)
2. Create an **A record** (or CNAME) pointing to the ALB DNS name
3. Update `APP_BASE_URL` to `https://your-domain.com`
4. Redeploy the application

---

## 5. Health Checks

The application exposes health endpoints via Spring Boot Actuator:

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Overall health (DB + Redis connectivity) |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Performance metrics |

The Docker container also has a built-in `HEALTHCHECK` that pings `/actuator/health` every 30 seconds.

---

## 6. Monitoring Checklist

For production, monitor these:

- [ ] ALB target group health (all instances healthy)
- [ ] EC2 CPU and memory usage (CloudWatch)
- [ ] PostgreSQL connection count and disk usage
- [ ] Redis memory usage (should stay under `maxmemory`)
- [ ] Application logs (`docker compose logs -f app`)
- [ ] 4xx/5xx error rates (CloudWatch + ALB metrics)

---

## 7. Scaling Strategy

### Vertical Scaling

Upgrade the EC2 instance type (t3.small -> t3.medium -> t3.large) for more CPU/RAM.

### Horizontal Scaling

1. Create an **AMI** from your configured EC2 instance
2. Use an **Auto Scaling Group** with the AMI
3. Set scaling policies based on CPU utilization or request count
4. The ALB automatically distributes traffic across instances
5. Redis and PostgreSQL are shared (consider AWS ElastiCache and RDS for managed services)

### Managed Services (Recommended for Production)

| Service | AWS Managed Alternative |
|---------|------------------------|
| PostgreSQL container | Amazon RDS for PostgreSQL |
| Redis container | Amazon ElastiCache for Redis |
| Docker on EC2 | AWS ECS (Fargate) or EKS |

Using managed services removes the operational burden of database backups, patching, and failover.
