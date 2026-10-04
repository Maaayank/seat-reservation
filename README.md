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
| `GET /shows/{id}` | Per-seat status and counts. `available + held + confirmed == total`, read in one query. Public. |
| `POST /auth/tokens` | Mint user tokens in bulk (admin: `X-Admin-Key`). Body: `{"user_ids": ["u1", "u2"]}` (max 10,000). |
| `GET /auth/me` | Returns the caller's `user_id` from the token. |
| `POST /reservations/{id}/cancel` | Owner-only cancel. 200 with `status: "cancelled"`. Idempotent. Another user's reservation → 404. |
| `POST /shows/{id}/reserve` | Reserve seats for the token's user. Body: `{"seats": ["A12"]}`. Idempotency key in the `Idempotency-Key` header (or `idempotency_key` in the body). |

## Reserve semantics

- **All-or-nothing.** If any requested seat is taken, the whole request is declined and nothing changes.
- **201** `{reservation_id, show_id, user_id, seats, amount_paise, status: "confirmed"}`.
- **409** `seat_taken` · `per_user_limit` · `idempotency_key_reuse` (same key, different seats). Never a 5xx for a race.
- **Idempotency.** Keys are per user. Same key + same request → 201 with the original body and `Idempotent-Replayed: true`. Only successes are stored, so a declined key can be retried.
- **Cancel.** Frees only the seats that still point at that reservation, so a cancel can never release a seat that now belongs to someone else. The user's per-show count goes down, and the seats are bookable again at once.
- **Atomic decision.** One transaction: claim the idempotency key → add to the per-user quota row (guarded update) → lock the seat rows in label order (`SELECT … FOR UPDATE`) → write. One lock order everywhere, so no deadlocks. Cancel uses the same order (reservation row → quota row → seats by label).

## Auth

- Users: `Authorization: Bearer <jwt>` (HS256, `sub` = user id, 1 h TTL). Identity comes only from the token; any user id in a body is ignored.
- Admin: `X-Admin-Key: <ADMIN_API_KEY>` for `POST /shows` and `POST /auth/tokens`.
- Required env: `ADMIN_API_KEY`, `JWT_SECRET` (at least 32 bytes). The app refuses to start without them.

```sh
curl -s localhost:8080/auth/tokens -H 'Content-Type: application/json' -H 'X-Admin-Key: local-admin-key'   -d '{"user_ids":["alice","bob"]}'
```

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
