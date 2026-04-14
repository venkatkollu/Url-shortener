# Project Overview

## What This Project Does

This is a **high-performance URL shortening service** -- similar to Bitly or TinyURL -- that takes long URLs and generates compact, shareable links. When someone visits the short link, they are instantly redirected to the original URL.

What sets this project apart from a basic URL shortener is the engineering behind it: **Redis caching** for sub-millisecond reads, **rate limiting** to prevent abuse, **database indexing** for fast lookups, and a **containerized deployment** strategy ready for production on AWS.

## System Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Client     │────>│  Rate Limiter    │────>│  Spring Boot    │
│  (Browser/   │     │  (Redis-backed   │     │  Application    │
│   curl/app)  │     │   IP filter)     │     │                 │
└─────────────┘     └──────────────────┘     └────────┬────────┘
                                                       │
                                              ┌────────┴────────┐
                                              │                 │
                                     ┌────────▼──────┐  ┌──────▼───────┐
                                     │    Redis      │  │  PostgreSQL  │
                                     │   (Cache)     │  │  (Persistent │
                                     │               │  │   Storage)   │
                                     └───────────────┘  └──────────────┘
```

### Request Flow: Creating a Short URL

1. Client sends `POST /api/v1/shorten` with the original URL
2. The **Rate Limit Filter** checks if the client IP has exceeded the request limit (100/minute)
3. The **UrlShorteningService** saves the URL to PostgreSQL, getting back an auto-incremented ID
4. The ID is encoded using **Base62** to produce a short code (e.g., ID `12345` becomes `3d7`)
5. The mapping is **cached in Redis** with a TTL
6. The short URL is returned to the client

### Request Flow: Redirecting

1. Client visits `GET /{shortCode}`
2. Rate Limit Filter checks the IP
3. Service checks **Redis cache** first (cache-aside pattern)
4. On **cache hit**: returns the URL immediately (sub-millisecond)
5. On **cache miss**: queries PostgreSQL, caches the result, then returns
6. Click count is incremented in the database
7. Client receives a **302 redirect** to the original URL

## Tech Stack Rationale

### Why Spring Boot?

Spring Boot is the industry standard for Java microservices. It provides:
- Auto-configuration for database, Redis, and web server setup
- Built-in dependency injection for clean, testable code
- Spring Data JPA for simplified database operations
- Actuator for production-ready health checks and metrics

### Why PostgreSQL?

- **ACID compliance** ensures URL mappings are never lost or corrupted
- **BIGSERIAL** auto-increment provides the sequential IDs that Base62 encoding requires
- **Partial indexes** (on `expires_at`) speed up queries without wasting space on non-expiring URLs
- Battle-tested at scale by companies like Instagram, Spotify, and Reddit

### Why Redis?

URL shorteners are **extremely read-heavy** (typically 100:1 read-to-write ratio). Redis provides:
- **Sub-millisecond reads** from memory vs. disk-based PostgreSQL queries
- Built-in **TTL** for automatic cache expiration
- **Atomic INCR/EXPIRE** for implementing rate limiting without race conditions
- Reduces database load by serving repeated lookups from cache

### Why Docker?

- Reproducible environments across development, testing, and production
- Single command (`docker compose up`) to run the entire stack
- Multi-stage builds produce minimal, secure runtime images (~200MB)
- Same containers run on a laptop and on AWS EC2

## Package Structure

```
com.urlshortener
├── config/          Configuration beans (Redis, rate limiting)
├── controller/      REST API endpoints (URL operations, stats)
├── dto/             Data transfer objects (request/response shapes)
├── entity/          JPA entities (database table mappings)
├── exception/       Custom exceptions + global error handler
├── filter/          Servlet filters (rate limiting)
├── repository/      Spring Data JPA repositories (database queries)
└── service/         Business logic (URL shortening, caching, encoding)
```

This follows the **layered architecture** pattern:
- **Controller layer** handles HTTP concerns (request parsing, response formatting)
- **Service layer** contains business logic (encoding, caching, validation)
- **Repository layer** handles data persistence (JPA queries)
- **Filter layer** implements cross-cutting concerns (rate limiting)

## Key Metrics

| Metric | Target |
|--------|--------|
| Redirect latency (cache hit) | < 5ms |
| Redirect latency (cache miss) | < 50ms |
| Short code length | 1-7 characters for first ~3.5 trillion URLs |
| Rate limit | 100 requests/minute/IP |
| Cache TTL | 24 hours (default) |
