# Seat Reservation at Scale

Spring Boot 4 + PostgreSQL service that sells assigned seats correctly under concurrent load.

- **Live:** https://seat-reservation-vh11.onrender.com (Render free tier; the first request after idle can take ~60 s)
- **Write-up:** [`WRITEUP.md`](WRITEUP.md)
- **Design and decision log:** [`docs/DISCOVERY.md`](docs/DISCOVERY.md)
- **Deploy runbook:** [`docs/DEPLOY.md`](docs/DEPLOY.md)

## Run locally

Requirements: JDK 21, and Docker or Podman.

```sh
# Docker
docker compose up --build
# Podman
podman compose up --build
```

Service: `http://localhost:8080`.

## Endpoints

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
- **Fast-decline layers** (decline only, never grant): L2 in-memory sold set → L1 non-locking read → L3 one in-flight DB attempt per seat. They skip seats the requester owns, so idempotent retries still replay. Each can be switched off (`LAYER_SOLD_SET`, `LAYER_READ_CHECK`, `LAYER_SEAT_CLAIM`); the test suite runs with all on and all off. Metric: `reservations_decline_path_total{layer}`.
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

## Burst: reproduce the on-sale stampede

```sh
./burst.sh <BASE_URL> <ADMIN_KEY> [--profile smoke|full] [--max-in-flight N]
# or
make burst URL=<BASE_URL> KEY=<ADMIN_KEY> PROFILE=full
```

Runs with a local JDK 21, else in a container (Docker or Podman). Exit code 0 only if every check passes.

| Profile | Wave | Use |
|---|---|---|
| `smoke` (default) | 1,500 requests: 3 hot seats × 100 users, 20 retry groups × 5, 1,100 stampede | Free-tier live URL, CI smoke. |
| `full` | 20,000 requests: 10 hot seats × 500 users, 200 retry groups × 5, 14,000 stampede | Your own deploy or local compose. |

What it does, on one fresh show:

1. Waits for `/readyz` (reports cold-start time), creates the show, mints tokens in bulk.
2. **On-sale wave**, all requests released at once: hot-seat storm, skewed stampede (70% want rows A–C), idempotent retry groups (same key sent 5× in parallel). An invariant poller reads `GET /shows/{id}` every 200 ms during the wave.
3. Same key with different seats → expects 409.
4. Per-user limit: users send 10 parallel reserves on a limit-4 show → expects exactly 4.
5. Spoof: body `user_id` of another user → acts as the token user; the other user cannot cancel it.
6. Cancel racing rebookers on won seats.
7. Final reconciliation: counts add up, confirmed seats equal what clients were told, Prometheus counters equal client-observed outcomes, `seats` gauge equals the API.

Requests that get no HTTP response (timeouts, dropped connections) are reported separately as client-side errors, never as 5xx. A 5xx is labelled `(app)` when it carries this service's JSON error body (with `request_id`) and `(edge)` when it came from a proxy in front of it.

Behind a TLS-inspecting proxy on Windows, Java may fail with `PKIX path building failed`. Use the Windows trust store: `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT ./burst.sh ...`.

Measured locally (`full`, run inside the compose network):

```
on-sale wave: 20000 requests, {201=994, 201 (replay)=64, 409:seat_taken=18942}
latency p50 219 ms | p95 936 ms | p99 1254 ms
RESULT: PASS   (0 5xx, 10/10 hot seats with exactly one winner, metrics reconcile exactly)
```

> **Capacity note.** The live demo runs on Render's free tier: a shared ~0.1 CPU and 512 MB. It sleeps when idle, and the first request after idle can take ~60 s.
> - **Smoke profile:** passes live.
> - **Full profile (20k), live:** the app returned zero 5xx, and every correctness check held in every run (one winner per hot seat, invariant, reconciliation). Throughput is about 80 req/s, with p50 about 23 s.
> - **Free-tier limits under the full burst:** Render's edge returned about 1 5xx per run, and one of the last three runs restarted the instance. The instance is CPU-bound at this size.
> - **Clean full run:** use your own deploy, or run locally with `make up && make burst-in-network`.
> Bursting a local stack through the host port (`localhost:8080`) with thousands of connections can hit the container runtime's port forwarder (we saw dropped connections with Podman on Windows). The server never sees those requests; the tool reports them as client-side errors. Running inside the compose network avoids it.

## Observability

`docker compose up --build` (or `podman compose up --build`) also starts:

| URL | What |
|---|---|
| http://localhost:3000 | Grafana, anonymous view. Home dashboard "Seat Reservation" (`observability/grafana/dashboards/seat-reservation.json`). |
| http://localhost:9090 | Prometheus. Scrapes the app every 5 s. Alert rules: `observability/prometheus/alerts.yml`. |

Key metrics (`/actuator/prometheus`):

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total{show_id}` | Reservations created. |
| `reservations_declined_total{show_id,reason}` | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reuse`, `invalid`. |
| `reservations_cancelled_total{show_id}` | Cancels (repeat cancels not counted). |
| `seats{show_id,state}` | Seats per state, read from the DB. Matches `GET /shows/{id}`. |
| `seats_capacity{show_id}` | Total seats. `sum(seats) - sum(seats_capacity)` must be 0. |
| `reservations_decline_path_total{layer}` | Which layer declined a seat_taken request. |
| `http_server_requests_seconds_*`, `hikaricp_*`, `jvm_*` | Built-in. |

Counters are in memory and reset on restart; the `seats` gauges come from the DB. The DB is only queried for them after a write (at most once per second), so an idle service lets a serverless DB sleep.

Logs: JSON (ECS) on stdout via an async appender. Every line carries `request_id`; authenticated requests carry `user_id`. Each confirmed or replayed reserve, and each cancel, logs one INFO line (`reserve decided` / `cancel decided`) with `show_id`, `seats`, `outcome`. Declines and per-request access lines are DEBUG, because at INFO a burst spends a large share of its CPU and heap on them. Turn them on with `LOGGING_LEVEL_RESERVATION=DEBUG` and `LOGGING_LEVEL_ACCESS=DEBUG`. Set `METRICS_HISTOGRAM=false` to drop the latency histogram buckets.

## Project structure

Package-by-feature. Each feature folder holds its controller, service and repository.

```
src/main/java/com/paytm/seats/
├─ common/          error contract (ErrorCode, ApiException, handler), request id filter
├─ config/          typed settings (seats.*), clock
├─ auth/            JWT + admin key filters, token minting, AuthenticatedUser
├─ show/            create/read shows, ShowCatalog cache of immutable show data
├─ reservation/     ReservationService (flow) → ReserveTransaction (atomic decision)
│  │                CancelService, ReservationRepository (SQL), ReserveRequestValidator
│  └─ layers/       fast-decline layers L1/L2/L3 (decline only, never grant)
└─ observability/   business counters, DB-backed seat gauges
src/main/resources/db/migration/V1__schema.sql
burst/              load generator and invariant checker (separate Maven module)
observability/      Prometheus config + alert rules, Grafana dashboard
docs/DISCOVERY.md   design and decision log (D1..Dn)
```

Every package has a `package-info.java` describing its role; the root one maps the request path and the correctness rules.

Code style: spring-javaformat, enforced in the build. Fix with `./mvnw spring-javaformat:apply`.

## Test

```sh
./mvnw verify
```

Tests use Testcontainers (real PostgreSQL). With Podman on Windows, point Testcontainers at the Podman pipe first:

```sh
export DOCKER_HOST="npipe:////./pipe/podman-machine"   # use your machine's pipe name
./mvnw verify
```
