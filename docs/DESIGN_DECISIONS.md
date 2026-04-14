# Design Decisions

This document explains the "why" behind every major technical choice in the URL Shortener. These are the kinds of questions interviewers ask, and understanding them demonstrates real engineering thinking.

---

## 1. Base62 Encoding vs. Hashing

### Decision: Base62 encoding of auto-incremented database IDs

### Why Not Hashing (MD5/SHA)?

- **Collision risk**: Hash functions can produce collisions -- two different URLs generating the same short code. You'd need collision detection and retry logic.
- **Fixed length**: Hashes produce fixed-length outputs (MD5 = 32 hex chars). Even truncating to 7 characters gives collision probability that grows with scale.
- **No ordering**: Hashes are random, making debugging and database range queries harder.

### Why Base62?

- **Zero collisions**: Each database ID is unique, so each Base62 encoding is unique. No collision handling needed.
- **Compact**: Base62 uses `[0-9a-zA-Z]` (62 characters). A 7-character code can represent 62^7 = **3.5 trillion** unique URLs.
- **Monotonically increasing**: Short codes grow in length as IDs increase, so early codes are very short (`1` -> `"1"`, `62` -> `"10"`).
- **Reversible**: You can decode a short code back to the database ID, useful for debugging.

### How It Works

```
Database ID: 12345
Base62 Encoding: 12345 -> "3d7"

12345 ÷ 62 = 199 remainder 7  -> '7'
  199 ÷ 62 =   3 remainder 13 -> 'd'
    3 ÷ 62 =   0 remainder 3  -> '3'

Result: "3d7"
```

### Trade-off

The downside is that short codes are **sequential and predictable**. An attacker could enumerate URLs by incrementing IDs. For a public URL shortener this is acceptable (the URLs are meant to be shared anyway). For sensitive use cases, you'd add a random salt or use a different strategy.

---

## 2. Redis Cache-Aside Pattern

### Decision: Cache-aside (lazy loading) with Redis

### What Is Cache-Aside?

```
Read Request:
  1. Check Redis cache
  2. If HIT  -> return cached value (fast path)
  3. If MISS -> query PostgreSQL, store in Redis, return value

Write Request:
  1. Write to PostgreSQL
  2. Write to Redis cache (with TTL)
```

### Why Cache-Aside (vs. Write-Through, Write-Behind)?

- **Simplicity**: The application explicitly controls what gets cached and when. No framework magic.
- **Resilience**: If Redis goes down, the app still works (just slower, hitting the DB directly).
- **Read-optimized**: URL shorteners are ~100:1 read-to-write. Cache-aside excels at this pattern.
- **Memory-efficient**: Only frequently-accessed URLs stay in cache. Cold URLs expire naturally via TTL.

### Why Not Cache-Through?

Write-through caching (where writes always go through the cache to the DB) adds complexity and couples the cache to write operations. Since writes are infrequent in a URL shortener, the added complexity isn't justified.

### TTL Strategy

- Default TTL: **24 hours** -- balances memory usage with cache hit rate
- Expiring URLs: cache TTL matches the URL's expiration time
- On deletion: cache entry is explicitly evicted

---

## 3. Rate Limiting at the Filter Level

### Decision: IP-based rate limiting using a Servlet Filter with Redis counters

### Why a Servlet Filter?

- Filters execute **before** the request reaches Spring controllers
- Rate-limited requests never touch the service or database layer
- Minimal overhead: a single Redis INCR + conditional check per request

### Why Not Spring Security / Bucket4j / Guava RateLimiter?

- **Spring Security**: Overkill for simple IP-based limiting. We don't need authentication.
- **Bucket4j**: Good library, but adds a dependency for something achievable in ~40 lines of code.
- **Guava RateLimiter**: In-memory only. Doesn't work across multiple app instances behind a load balancer.

### Why Redis for Rate Limiting?

- **Distributed**: Works correctly when multiple app instances share the same Redis. Essential for horizontal scaling behind an ALB.
- **Atomic**: `INCR` is atomic, preventing race conditions when concurrent requests from the same IP arrive.
- **Auto-expiry**: `EXPIRE` automatically resets counters, no cleanup jobs needed.

### Algorithm: Fixed Window

```
Key: "rate_limit:{ip_address}"
On each request:
  1. INCR key (atomic increment)
  2. If count == 1, SET EXPIRE 60 seconds
  3. If count > 100, reject with 429
```

This is a fixed-window algorithm. It's simple and works well for most cases. The trade-off is that a burst of requests at a window boundary could allow up to 2x the limit. For stricter control, a sliding window or token bucket algorithm would be used instead.

---

## 4. Database Indexing Strategy

### Decision: Unique index on `short_code`, partial index on `expires_at`

### Indexes Created

```sql
-- Unique constraint also creates an index
CONSTRAINT uq_short_code UNIQUE (short_code)

-- Explicit index for lookups (redundant with unique constraint, but explicit)
CREATE INDEX idx_url_mappings_short_code ON url_mappings (short_code);

-- Partial index: only indexes rows where expires_at is not null
CREATE INDEX idx_url_mappings_expires_at ON url_mappings (expires_at)
  WHERE expires_at IS NOT NULL;
```

### Why Index `short_code`?

Every redirect request queries by `short_code`. Without an index, PostgreSQL would scan the entire table (O(n)). With a B-tree index, lookups are O(log n) -- effectively constant time for practical table sizes.

### Why a Partial Index on `expires_at`?

Most URLs won't have an expiration date. A partial index only includes rows where `expires_at IS NOT NULL`, so:
- **Smaller index size**: Only a fraction of rows are indexed
- **Faster writes**: Inserts of non-expiring URLs don't need to update this index
- **Useful for cleanup**: A future background job could efficiently query for expired URLs to delete

### Why Not Index `original_url`?

We never query by original URL. Indexing a VARCHAR(2048) column would waste significant disk space and slow down writes for zero benefit.

---

## 5. Expiration Handling

### Decision: Lazy expiration (check-on-read) rather than eager deletion

### How It Works

Expired URLs are **not** proactively deleted from the database. Instead:
1. When a redirect request comes in, the service checks `expires_at`
2. If expired, it returns **410 Gone** and evicts the cache entry
3. The database row remains (could be cleaned up by a scheduled job)

### Why Lazy Over Eager?

- **Simpler**: No background scheduler, no distributed locking for cleanup jobs
- **Immediate consistency**: The moment a URL expires, the next request gets a 410
- **Audit trail**: Expired URLs remain in the database for analytics

### Future Enhancement

For production, you'd add a scheduled job (`@Scheduled` in Spring) to periodically delete expired rows and reclaim disk space. The partial index on `expires_at` makes this query efficient.

---

## 6. 302 Redirect vs. 301 Redirect

### Decision: HTTP 302 (Found) instead of 301 (Moved Permanently)

### Why 302?

- **302 (temporary redirect)**: Browsers and proxies will **not cache** the redirect. Every visit hits our server.
- **301 (permanent redirect)**: Browsers cache the redirect. Subsequent visits bypass our server entirely.

For a URL shortener, 302 is better because:
- We can **track clicks** accurately (every visit hits our server)
- We can **change the destination** without users getting stale cached redirects
- Expired URLs are respected immediately (no cached 301 bypassing expiration)

### Trade-off

302 adds a tiny bit of latency vs. a cached 301. For analytics and correctness, this is the right choice. Production URL shorteners (Bitly, TinyURL) all use 302 or 307 for this reason.

---

## 7. Layered Architecture

### Decision: Controller -> Service -> Repository layered architecture

### Why This Pattern?

- **Separation of concerns**: Each layer has a single responsibility
- **Testability**: Services can be unit-tested with mocked repositories. Controllers can be integration-tested.
- **Flexibility**: Swapping PostgreSQL for another database only requires changing the repository layer

### Layer Responsibilities

| Layer | Responsibility | Example |
|-------|---------------|---------|
| Controller | HTTP handling, request validation, response formatting | Parse JSON, return 302 |
| Service | Business logic, caching, encoding | Base62 encode, check expiry |
| Repository | Data access, SQL queries | `findByShortCode()` |
| Filter | Cross-cutting concerns | Rate limiting |

---

## 8. Error Handling Strategy

### Decision: Global exception handler with structured error responses

Using `@RestControllerAdvice` with custom exceptions:

- **Consistent error format**: Every error returns the same JSON structure (`timestamp`, `status`, `error`, `message`)
- **Appropriate HTTP codes**: 404 for not found, 409 for conflicts, 410 for expired, 429 for rate limits
- **No stack traces leaked**: The global handler catches unexpected exceptions and returns a generic 500

This approach is cleaner than try-catch blocks in every controller method and ensures clients always get predictable error responses.
