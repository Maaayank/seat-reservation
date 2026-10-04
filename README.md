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
| `POST /shows` | Create a show (admin: `X-Admin-Key`). Body: `name`, `seats[]`, `price_paise` (integer), optional `per_user_limit` (default 4). |
| `GET /shows/{id}` | Per-seat status and counts. `available + held + confirmed == total`, read in one query. |

Errors use one shape: `{"error": "<code>", "message": "...", "request_id": "..."}`.

Local admin key (compose default): `local-admin-key`. Override with `ADMIN_API_KEY`. The app does not start without one.

```sh
curl -s localhost:8080/shows -H 'Content-Type: application/json' -H 'X-Admin-Key: local-admin-key'   -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'
```

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
