# Seat Reservation at Scale

Spring Boot 4 + PostgreSQL service that sells assigned seats correctly under concurrent load.
Design and decisions: [`docs/DISCOVERY.md`](docs/DISCOVERY.md).

> Work in progress. Sections below grow phase by phase.

## Run locally

Requirements: JDK 21, and Docker or Podman.

```sh
# Docker
docker compose up --build
# Podman
podman compose up --build
```

Service: `http://localhost:8080`.

## Endpoints (so far)

| Path | Purpose |
|---|---|
| `GET /livez` | Liveness. Process is up. No dependency check. |
| `GET /readyz` | Readiness. Checks the database. Returns 503 when the DB is down. |
| `GET /actuator/prometheus` | Prometheus metrics. |

Every response carries `X-Request-Id`. A well-formed client value is echoed; otherwise one is generated.
Logs are JSON (ECS) on stdout and include `request_id`.

## Test

```sh
./mvnw verify
```

Tests use Testcontainers (real PostgreSQL). With Podman on Windows, point Testcontainers at the Podman pipe first:

```sh
export DOCKER_HOST="npipe:////./pipe/podman-machine"   # use your machine's pipe name
./mvnw verify
```
