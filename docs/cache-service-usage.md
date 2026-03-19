# Cache Service — Usage Guide

This guide explains how to run the Cache Service and how an orchestrator should use it during an ETL flow.

For the architecture and design rationale, see [cache-service.md](./cache-service.md).

## 1. What This Service Does

The Cache Service stores the result of an ETL computation behind a deterministic cache key.

The orchestrator is responsible for:

* generating the cache key
* checking whether a cached result already exists
* acquiring a lock before starting a computation
* publishing the artifact reference once the computation finishes

The ETL services themselves do not talk to the cache directly.

---

## 2. Default Configuration

Current default configuration:

```properties
spring.application.name=cacheservice

cache.store.type=redis
cache.redis.key-prefix.cache=c:
cache.redis.key-prefix.lock=l:

spring.data.redis.host=localhost
spring.data.redis.port=6379
```

This means:

* the service expects Redis on `localhost:6379`
* cache entries are stored with the prefix `c:`
* locks are stored with the prefix `l:`

---

## 3. Running The Service

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

## 4. API Overview

Base path:

```text
/v1/cache
```

Endpoints:

* `GET /v1/cache/{namespace}/{key}`
* `POST /v1/cache/{namespace}/{key}/lock`
* `POST /v1/cache/{namespace}/{key}/publish`
* `DELETE /v1/cache/{namespace}/{key}`

`namespace` lets you separate cache spaces, for example:

* `extract`
* `transform`
* `load`
* `customers-v2`

`key` should be deterministic and URL-safe.

---

## 5. Recommended Orchestrator Flow

The normal orchestrator flow is:

1. Build a deterministic cache key.
2. Call `GET /v1/cache/{namespace}/{key}`.
3. If the response is `200 READY`, reuse the cached artifact.
4. If the response is `404 MISS`, try to acquire the lock.
5. If the lock is acquired, execute the ETL step.
6. Store the result artifact in object storage.
7. Call `POST /publish` with the lock token and artifact URI.
8. Return the published artifact to the caller.

If another orchestrator gets `409 COMPUTING`, it should wait and retry later.

---

## 6. Endpoint Details

### 6.1 Check cache

Request:

```bash
curl -i http://localhost:8080/v1/cache/extract/job-123
```

#### Cache miss

Response:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

```json
{
  "namespace": "extract",
  "key": "job-123",
  "status": "MISS",
  "artifactUri": null,
  "metadata": null,
  "updatedAt": null,
  "owner": null
}
```

#### Cache currently computing

Response:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "namespace": "extract",
  "key": "job-123",
  "status": "COMPUTING",
  "artifactUri": null,
  "metadata": null,
  "updatedAt": "2026-03-19T07:51:07.874Z",
  "owner": "orchestrator"
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
  "namespace": "extract",
  "key": "job-123",
  "status": "READY",
  "artifactUri": "s3://bucket/extract/job-123.parquet",
  "metadata": {
    "rows": 42
  },
  "updatedAt": "2026-03-19T07:51:07.880Z",
  "owner": null
}
```

### 6.2 Acquire the lock

Request:

```bash
curl -i -X POST http://localhost:8080/v1/cache/extract/job-123/lock \
  -H "Content-Type: application/json" \
  -d '{
    "owner": "orchestrator",
    "leaseMs": 300000
  }'
```

Successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "token": "8cd47376-0ccd-4cc6-a1c5-e2d278be0cf0",
  "owner": "orchestrator",
  "leaseMs": 300000,
  "expiresAt": "2026-03-19T07:56:07.871Z"
}
```

Conflict response:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "timestamp": "2026-03-19T07:51:07.877Z",
  "status": 409,
  "error": "Conflict",
  "message": "A cache entry is already ready or currently being computed.",
  "path": "/v1/cache/extract/job-123/lock"
}
```

Use a `leaseMs` value long enough for the ETL step to finish safely.

### 6.3 Publish a computed result

After the ETL step completes and the artifact is stored, publish the result:

```bash
curl -i -X POST http://localhost:8080/v1/cache/extract/job-123/publish \
  -H "Content-Type: application/json" \
  -d '{
    "token": "8cd47376-0ccd-4cc6-a1c5-e2d278be0cf0",
    "artifactUri": "s3://bucket/extract/job-123.parquet",
    "metadata": {
      "rows": 42,
      "format": "parquet"
    },
    "ttlSeconds": 86400
  }'
```

Successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "namespace": "extract",
  "key": "job-123",
  "status": "READY",
  "artifactUri": "s3://bucket/extract/job-123.parquet",
  "metadata": {
    "rows": 42,
    "format": "parquet"
  },
  "updatedAt": "2026-03-19T07:51:07.880Z",
  "owner": null
}
```

Invalid token response:

```http
HTTP/1.1 403 Forbidden
Content-Type: application/json
```

```json
{
  "timestamp": "2026-03-19T07:51:07.878Z",
  "status": 403,
  "error": "Forbidden",
  "message": "The provided lock token is invalid for this cache entry.",
  "path": "/v1/cache/extract/job-123/publish"
}
```

Expired or missing lock response:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "timestamp": "2026-03-19T07:51:07.837Z",
  "status": 409,
  "error": "Conflict",
  "message": "The lock no longer exists. The computation must be retried.",
  "path": "/v1/cache/extract/job-123/publish"
}
```

### 6.4 Delete a cache entry

You can manually evict an entry:

```bash
curl -i -X DELETE http://localhost:8080/v1/cache/extract/job-123
```

Response:

```http
HTTP/1.1 204 No Content
```

This removes both the cache entry and its lock, if present.

---

## 7. Example End-To-End Session

### Step 1: check the cache

```bash
curl -i http://localhost:8080/v1/cache/extract/customer-import-2026-03-19
```

If the result is `404 MISS`, continue.

### Step 2: acquire the lock

```bash
curl -i -X POST http://localhost:8080/v1/cache/extract/customer-import-2026-03-19/lock \
  -H "Content-Type: application/json" \
  -d '{
    "owner": "orchestrator",
    "leaseMs": 300000
  }'
```

If the result is `200`, keep the returned token.

If the result is `409`, another worker is already computing the result. Retry later.

### Step 3: run ETL and store the artifact

Example artifact URI:

```text
s3://etl-artifacts/extract/customer-import-2026-03-19.parquet
```

### Step 4: publish the artifact

```bash
curl -i -X POST http://localhost:8080/v1/cache/extract/customer-import-2026-03-19/publish \
  -H "Content-Type: application/json" \
  -d '{
    "token": "LOCK_TOKEN_HERE",
    "artifactUri": "s3://etl-artifacts/extract/customer-import-2026-03-19.parquet",
    "metadata": {
      "rows": 120004,
      "source": "crm",
      "partition": "2026-03-19"
    },
    "ttlSeconds": 86400
  }'
```

### Step 5: read the cached result

```bash
curl -i http://localhost:8080/v1/cache/extract/customer-import-2026-03-19
```

You should now receive `200 READY` and the published `artifactUri`.

---

## 8. Input Validation Rules

### Lock request

Rules:

* `owner` must not be blank
* `leaseMs` must be greater than `0`

### Publish request

Rules:

* `token` must not be blank
* `artifactUri` must not be blank
* `ttlSeconds` must not be null
* `ttlSeconds` must be greater than `0`
* `metadata` is optional

Invalid payloads return:

```http
HTTP/1.1 400 Bad Request
```

Example:

```json
{
  "timestamp": "2026-03-19T07:51:07.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "owner must not be blank; leaseMs must be greater than 0",
  "path": "/v1/cache/extract/job-123/lock"
}
```

---

## 9. Key Design Recommendations

The cache key should:

* be deterministic
* be stable across retries
* identify the business request, not the transport request

Include:

* business parameters
* date or partition window
* upstream data fingerprint
* pipeline or service version

Exclude:

* request IDs
* timestamps generated at runtime
* random values

A common approach is:

```text
sha256(canonical_json(request))
```

---

## 10. Operational Notes

* The service itself does not compute ETL data.
* The service stores artifact references, not the artifact content.
* TTL is applied when `publish` is called.
* Locks expire automatically based on `leaseMs`.
* If the lock expires before publish, the publish request is rejected and the computation should be retried.

---

## 11. Typical Integration Pseudocode

```text
key = buildDeterministicKey(request)

result = GET /v1/cache/{namespace}/{key}

if result.status == READY:
    return result.artifactUri

if result.status == COMPUTING:
    retryLater()

lock = POST /v1/cache/{namespace}/{key}/lock

if lock not acquired:
    retryLater()

artifactUri = runEtlAndStoreArtifact()

POST /v1/cache/{namespace}/{key}/publish
  token = lock.token
  artifactUri = artifactUri
  metadata = resultMetadata
  ttlSeconds = 86400

return artifactUri
```
