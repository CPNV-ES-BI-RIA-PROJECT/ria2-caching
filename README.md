# Cache Service

Spring Boot cache service for RIA2. It provides a small HTTP API that lets an orchestrator coordinate cache hits, cache misses, and in-progress computations through Redis-backed locks.

The service does not perform the underlying work itself. It only tracks cache state for a deterministic `{namespace}/{key}` pair:

- `MISS`
- `COMPUTING`
- `READY`

## What It Does

The intended flow is:

1. An orchestrator builds a deterministic cache key.
2. It calls `GET /v1/cache/{namespace}/{key}`.
3. If the entry is ready, the service returns `200 READY`.
4. If the entry is missing, the service acquires the lock internally and returns `404 MISS`.
5. The orchestrator runs the expensive work.
6. The orchestrator calls `POST /v1/cache/{namespace}/{key}/publish`.
7. Later reads return `200 READY` until the TTL expires.

If another caller checks the same key while work is already in progress, it receives `409 COMPUTING`.

## Stack

- Java 21
- Spring Boot 4.0.3
- Maven
- Redis for the default cache store
- Optional in-memory store for quick local testing

## Project Layout

- [`src/main/java`](./src/main/java): application, API, service, config, and store implementations
- [`src/main/resources/application.properties`](./src/main/resources/application.properties): default configuration
- [`docs/cache-service.md`](./docs/cache-service.md): architecture and technical notes
- [`docs/cache-service-usage.md`](./docs/cache-service-usage.md): detailed usage guide
- [`docker-compose.yml`](./docker-compose.yml): local container setup for the app and Redis

## Requirements

- Java 21
- Maven 3.9+
- Docker, if you want to run Redis or the whole stack in containers

## Configuration

The application reads its settings from environment variables. An example file is included at [`.env.example`](./.env.example).

Common variables:

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port for the service |
| `CACHE_STORE_TYPE` | `redis` | Store backend: `redis` or `in-memory` |
| `CACHE_DEFAULT_LEASE_MS` | `300000` | Lock lease duration in milliseconds |
| `CACHE_DEFAULT_TTL_SECONDS` | `86400` | TTL applied when a key is published |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host for local runs |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `CACHE_KEY_PREFIX_CACHE` | `c:` | Redis prefix for cache entries |
| `CACHE_KEY_PREFIX_LOCK` | `l:` | Redis prefix for locks |

For Docker Compose, copy `.env.example` to `.env` and keep `SPRING_DATA_REDIS_HOST=redis`.

## Run Locally

### Option 1: Redis + Spring Boot

Start Redis:

```bash
docker run --name cache-redis -p 6379:6379 redis:7.2-alpine
```

Start the application:

```bash
mvn spring-boot:run
```

The service will be available at `http://localhost:8080`.

### Option 2: In-memory Mode

For quick experiments without Redis:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--cache.store.type=in-memory"
```

This mode is useful for local testing, but Redis is the default and intended runtime backend.

## Run With Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

This starts:

- `cache-service`
- `redis`

Stop the stack:

```bash
docker compose down
```

Remove containers and volumes:

```bash
docker compose down -v
```

## API Summary

Base path: `/v1/cache`

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/{namespace}/{key}` | Read cache state |
| `POST` | `/{namespace}/{key}/lock` | Explicitly acquire a lock |
| `POST` | `/{namespace}/{key}/publish` | Mark the key as ready |
| `DELETE` | `/{namespace}/{key}` | Remove cache entry and lock |

Typical responses:

- `200 READY`
- `404 MISS`
- `409 COMPUTING`
- `204 No Content` for delete

Example:

```bash
curl -i http://localhost:8080/v1/cache/reports/job-123
curl -i -X POST http://localhost:8080/v1/cache/reports/job-123/publish
curl -i -X DELETE http://localhost:8080/v1/cache/reports/job-123
```

## Development

Run tests:

```bash
mvn test
```

Build the jar:

```bash
mvn package
```

## Documentation

For more detail, see:

- [Usage guide](./docs/cache-service-usage.md)
- [Technical documentation](./docs/cache-service.md)
