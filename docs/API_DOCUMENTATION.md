# API Documentation

Base URL (Docker Compose): `http://localhost:8021`. Local `./gradlew bootRun`: `http://localhost:8081`.

## Endpoints

---

### 1. Create Short URL

**`POST /api/v1/shorten`**

Creates a shortened URL. Optionally accepts a custom alias and an expiration time.

#### Request Body

```json
{
  "url": "https://www.example.com/very/long/path",
  "customAlias": "my-link",
  "expiresInMinutes": 1440
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | string | Yes | The original URL to shorten. Must be a valid URL. Max 2048 characters. |
| `customAlias` | string | No | Custom short code (3-30 chars, alphanumeric + hyphens/underscores). |
| `expiresInMinutes` | number | No | Minutes until the URL expires. Omit for no expiration. |

#### Success Response (201 Created)

```json
{
  "shortCode": "my-link",
  "shortUrl": "http://localhost:8021/my-link",
  "originalUrl": "https://www.example.com/very/long/path",
  "createdAt": "2026-03-23T10:30:00",
  "expiresAt": "2026-03-24T10:30:00"
}
```

#### Error Responses

**400 Bad Request** -- Invalid input:
```json
{
  "timestamp": "2026-03-23T10:30:00",
  "status": 400,
  "error": "Validation Failed",
  "errors": {
    "url": "Must be a valid URL"
  }
}
```

**409 Conflict** -- Custom alias already taken:
```json
{
  "timestamp": "2026-03-23T10:30:00",
  "status": 409,
  "error": "Conflict",
  "message": "Custom alias already taken: my-link"
}
```

#### cURL Example

```bash
# Basic URL shortening
curl -X POST http://localhost:8021/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.google.com"}'

# With custom alias and 24-hour expiration
curl -X POST http://localhost:8021/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.google.com", "customAlias": "goog", "expiresInMinutes": 1440}'
```

---

### 2. Redirect to Original URL

**`GET /{shortCode}`**

Redirects the client to the original URL associated with the given short code. Increments the click counter.

#### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `shortCode` | string | The short code or custom alias |

#### Success Response (302 Found)

```
HTTP/1.1 302
Location: https://www.google.com
```

The client's browser will automatically follow the redirect.

#### Error Responses

**404 Not Found** -- Short code does not exist:
```json
{
  "timestamp": "2026-03-23T10:30:00",
  "status": 404,
  "error": "Not Found",
  "message": "URL not found for short code: xyz123"
}
```

**410 Gone** -- URL has expired:
```json
{
  "timestamp": "2026-03-23T10:30:00",
  "status": 410,
  "error": "Gone",
  "message": "URL has expired for short code: old-link"
}
```

#### cURL Example

```bash
# Follow the redirect
curl -L http://localhost:8021/goog

# See the redirect header without following
curl -v http://localhost:8021/goog
```

---

### 3. Get URL Statistics

**`GET /api/v1/urls/{shortCode}/stats`**

Returns statistics for a short URL including click count and creation date.

#### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `shortCode` | string | The short code or custom alias |

#### Success Response (200 OK)

```json
{
  "shortCode": "goog",
  "shortUrl": "http://localhost:8021/goog",
  "originalUrl": "https://www.google.com",
  "createdAt": "2026-03-23T10:30:00",
  "expiresAt": "2026-03-24T10:30:00",
  "clickCount": 42
}
```

#### Error Responses

**404 Not Found** -- Short code does not exist.

#### cURL Example

```bash
curl http://localhost:8021/api/v1/urls/goog/stats
```

---

### 4. Delete Short URL

**`DELETE /api/v1/urls/{shortCode}`**

Permanently deletes a short URL and removes it from the cache.

#### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `shortCode` | string | The short code or custom alias |

#### Success Response (204 No Content)

Empty response body.

#### Error Responses

**404 Not Found** -- Short code does not exist.

#### cURL Example

```bash
curl -X DELETE http://localhost:8021/api/v1/urls/goog
```

---

### 5. Health Check

**`GET /actuator/health`**

Returns the health status of the application including database and Redis connectivity.

#### Success Response (200 OK)

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL"
      }
    },
    "redis": {
      "status": "UP"
    }
  }
}
```

---

## Rate Limiting

All endpoints (except `/actuator/*`) are rate-limited to **100 requests per minute per IP address**.

When the limit is exceeded, the API returns:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/json

{
  "timestamp": "2026-03-23T10:30:00",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please try again later."
}
```

The `Retry-After` header indicates how many seconds to wait before retrying.

## Common HTTP Status Codes

| Code | Meaning |
|------|---------|
| 201 | Short URL created successfully |
| 204 | URL deleted successfully |
| 302 | Redirect to original URL |
| 400 | Invalid request (validation error) |
| 404 | Short code not found |
| 409 | Custom alias already taken |
| 410 | URL has expired |
| 429 | Rate limit exceeded |
| 500 | Internal server error |
