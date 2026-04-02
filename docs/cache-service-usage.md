# Cache Service — Usage Guide

This guide explains how to run the Cache Service and how an orchestrator should use it.

For the architecture and design rationale, see [cache-service.md](./cache-service.md).

## Table of Contents

- [Cache Service — Usage Guide](#cache-service--usage-guide)
  - [Table of Contents](#table-of-contents)
  - [1. What This Service Does](#1-what-this-service-does)
  - [2. Configuration Via `.env`](#2-configuration-via-env)
  - [3. Default Configuration](#3-default-configuration)
  - [4. Running The Service](#4-running-the-service)
    - [Start Redis](#start-redis)
    - [Start the Spring Boot application](#start-the-spring-boot-application)
    - [Override configuration](#override-configuration)
  - [5. Running With Docker](#5-running-with-docker)
  - [6. API Overview](#6-api-overview)
  - [7. Recommended Orchestrator Flow](#7-recommended-orchestrator-flow)
  - [8. Endpoint Details](#8-endpoint-details)
    - [8.1 Check cache](#81-check-cache)
      - [Cache miss](#cache-miss)
      - [Cache currently computing](#cache-currently-computing)
      - [Cache ready](#cache-ready)
    - [8.2 Acquire the lock](#82-acquire-the-lock)
    - [8.3 Publish the key](#83-publish-the-key)
    - [8.4 Delete a cache entry](#84-delete-a-cache-entry)
    - [8.5 Flush the whole cache](#85-flush-the-whole-cache)
  - [9. Example Session](#9-example-session)
    - [Step 1: check the cache](#step-1-check-the-cache)
    - [Step 2: acquire the lock](#step-2-acquire-the-lock)
    - [Step 3: execute the work](#step-3-execute-the-work)
    - [Step 4: publish the key](#step-4-publish-the-key)
    - [Step 5: read the cached result](#step-5-read-the-cached-result)
  - [10. Input Validation Rules](#10-input-validation-rules)
  - [11. Key Design Recommendations](#11-key-design-recommendations)
  - [12. Operational Notes](#12-operational-notes)
  - [13. Typical Integration Pseudocode](#13-typical-integration-pseudocode)

## 1. What This Service Does

The Cache Service stores the state of a computation behind a deterministic cache key.

The orchestrator is responsible for:

* generating the cache key
* checking whether a cached result already exists
* executing work when the cache is missing
* publishing the key once the computation finishes

---

## 2. Configuration Via `.env`

The application is configured through environment variables.

Create a `.env` file at the project root:

```dotenv
SPRING_APPLICATION_NAME=cacheservice
SERVER_PORT=8080

CACHE_STORE_TYPE=redis
CACHE_DEFAULT_LEASE_MS=300000
CACHE_DEFAULT_TTL_SECONDS=86400
CACHE_KEY_PREFIX_CACHE=c:
CACHE_KEY_PREFIX_LOCK=l:

SPRING_DATA_REDIS_HOST=redis
SPRING_DATA_REDIS_PORT=6379

APP_CONTAINER_NAME=cache-service
REDIS_CONTAINER_NAME=cache-redis
REDIS_IMAGE=redis:7.2-alpine
REDIS_PORT=6379
```

An example file is also provided in `.env.example`.

The Spring Boot app reads these values through `application.properties`.

## 3. Default Configuration

Current default configuration:

```properties
spring.application.name=${SPRING_APPLICATION_NAME:cacheservice}
server.port=${SERVER_PORT:8080}

cache.store.type=${CACHE_STORE_TYPE:redis}
cache.operation.default-lease-ms=${CACHE_DEFAULT_LEASE_MS:300000}
cache.operation.default-ttl-seconds=${CACHE_DEFAULT_TTL_SECONDS:86400}
cache.redis.key-prefix.cache=${CACHE_KEY_PREFIX_CACHE:c:}
cache.redis.key-prefix.lock=${CACHE_KEY_PREFIX_LOCK:l:}

spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}
spring.data.redis.port=${SPRING_DATA_REDIS_PORT:6379}
```

This means:

* the service expects Redis on `localhost:6379`
* cache entries are stored with the prefix `c:`
* locks are stored with the prefix `l:`

---

## 4. Running The Service

### Start Redis

You need a Redis instance running before starting the application.

Example with Docker:

```bash
docker run --name cache-redis -p 6379:6379 redis:7
```

### Start the Spring Boot application

From the project root:

```bash
mvn spring-boot:run
```

By default the HTTP server will be available on:

```text
http://localhost:8080
```

### Override configuration

You can override properties when starting the app:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.data.redis.host=redis --spring.data.redis.port=6379"
```

For local testing without Redis, the project also supports:

```properties
cache.store.type=in-memory
```

This mode is mainly intended for tests and quick local experiments.

---

## 5. Running With Docker

From the project root:

```bash
docker compose up --build
```

This starts:

* the Spring Boot cache service
* a Redis container

The cache service will be available on:

```bash
http://localhost:8080
```

To stop everything:

```bash
docker compose down
```

To stop and remove Redis data as well:

```bash
docker compose down -v
```

---

## 6. API Overview

Base path:

```bash
/v1/cache
```

Endpoints:

* `GET /v1/cache/{namespace}/{key}`
* `POST /v1/cache/{namespace}/{key}/lock`
* `POST /v1/cache/{namespace}/{key}/publish`
* `DELETE /v1/cache`
* `DELETE /v1/cache/{namespace}/{key}`

`namespace` lets you separate cache spaces, for example:

* `reports`
* `imports`
* `aggregations`
* `customers-v2`

This is mainly here to split the cache across multiple orchestrated contexts if more are added later.

`key` should be deterministic and URL-safe.

---

## 7. Recommended Orchestrator Flow

The normal orchestrator flow is:

1. Build a deterministic cache key.
2. Call `GET /v1/cache/{namespace}/{key}`.
3. If the response is `200 READY`, skip the work.
4. If the response is `404 MISS`, the cache service has already started the lock workflow for this key.
5. Execute the work.
6. Call `POST /publish`.
7. A later `GET` returns `200 READY`.
8. Optionally call `DELETE` to flush the cache.

If another orchestrator gets `409 COMPUTING`, it should wait and retry later.

---

## 8. Endpoint Details

### 8.1 Check cache

Request:

```bash
curl -i http://localhost:8080/v1/cache/reports/job-123
```

#### Cache miss

Response:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

```json
{
  "namespace": "reports",
  "key": "job-123",
  "status": "MISS",
  "updatedAt": null
}
```

When this happens, the service also starts the internal lock workflow for this key.

#### Cache currently computing

Response:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "namespace": "reports",
  "key": "job-123",
  "status": "COMPUTING",
  "updatedAt": "2026-03-19T10:33:21.326Z"
}
```

#### Cache ready

Response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "namespace": "reports",
  "key": "job-123",
  "status": "READY",
  "updatedAt": "2026-03-19T10:33:21.331Z"
}
```

### 8.2 Acquire the lock

This endpoint is still public, but in the normal orchestrator flow it is optional because `GET` on a miss already starts the lock workflow.

Request:

```bash
curl -i -X POST http://localhost:8080/v1/cache/reports/job-123/lock
```

Successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "namespace": "reports",
  "key": "job-123",
  "status": "COMPUTING",
  "leaseMs": 300000,
  "expiresAt": "2026-03-19T10:38:21.323Z"
}
```

Conflict response:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "timestamp": "2026-03-19T10:33:21.328Z",
  "status": 409,
  "error": "Conflict",
  "message": "A cache entry is already ready or currently being computed.",
  "path": "/v1/cache/reports/job-123/lock"
}
```

The lease duration is configured through `CACHE_DEFAULT_LEASE_MS`.

### 8.3 Publish the key

After the work completes, publish the key:

```bash
curl -i -X POST http://localhost:8080/v1/cache/reports/job-123/publish
```

Successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "namespace": "reports",
  "key": "job-123",
  "status": "READY",
  "updatedAt": "2026-03-19T10:33:21.331Z"
}
```

Expired or missing lock response:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "timestamp": "2026-03-19T10:33:21.269Z",
  "status": 409,
  "error": "Conflict",
  "message": "The lock no longer exists. The computation must be retried.",
  "path": "/v1/cache/reports/job-123/publish"
}
```

The service uses `CACHE_DEFAULT_TTL_SECONDS` for the ready entry.

### 8.4 Delete a cache entry

You can manually evict an entry:

```bash
curl -i -X DELETE http://localhost:8080/v1/cache/reports/job-123
```

Response:

```http
HTTP/1.1 204 No Content
```

This removes both the cache entry and its lock, if present.

### 8.5 Flush the whole cache

You can remove every cache entry and every lock managed by this service:

```bash
curl -i -X DELETE http://localhost:8080/v1/cache
```

Response:

```http
HTTP/1.1 204 No Content
```

This is useful for manual resets in local or test environments.

---

## 9. Example Session

### Step 1: check the cache

```bash
curl -i http://localhost:8080/v1/cache/reports/monthly-summary-2026-03
```

If the result is `404 MISS`, continue directly with the work. The cache service has already started the lock workflow.

### Step 2: acquire the lock

```bash
curl -i -X POST http://localhost:8080/v1/cache/reports/monthly-summary-2026-03/lock
```

This step is optional. In the normal orchestrator flow, the first `GET` that returns `404 MISS` already starts the lock workflow.

If the result is `200`, the cache key is now marked as `COMPUTING`.

If the result is `409`, another worker is already computing the result. Retry later.

### Step 3: execute the work

### Step 4: publish the key

```bash
curl -i -X POST http://localhost:8080/v1/cache/reports/monthly-summary-2026-03/publish
```

### Step 5: read the cached result

```bash
curl -i http://localhost:8080/v1/cache/reports/monthly-summary-2026-03
```

You should now receive `200 READY`.

---

## 10. Input Validation Rules

The publish endpoint has no body and no query parameters.

---

## 11. Key Design Recommendations

The cache key should:

* be deterministic
* be stable across retries
* identify the business request, not the transport request

Include:

* business parameters
* date or partition window
* upstream data fingerprint
* workflow or service version

Exclude:

* request IDs
* timestamps generated at runtime
* random values

A common approach is:

```text
sha256(canonical_json(request))
```

---

## 12. Operational Notes

* The service itself does not compute the underlying work.
* The service stores only cache state in Redis.
* TTL is applied when `publish` is called.
* Locks expire automatically based on the configured default lease duration.
* If the lock expires before publish, the publish request is rejected and the computation should be retried.
* The public API no longer uses request bodies for `LOCK` or `PUBLISH`.

---

## 13. Typical Integration Pseudocode

```bash
key = buildDeterministicKey(request)

result = GET /v1/cache/{namespace}/{key}

if result.status == READY:
    return "READY"

if result.status == COMPUTING:
    retryLater()

if result.status == MISS:
    # the cache service has already started the lock workflow
    runWork()
    POST /v1/cache/{namespace}/{key}/publish
    return "READY"
```
