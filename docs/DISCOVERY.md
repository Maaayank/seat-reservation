# Discovery — Seat Reservation at Scale

Status: REVIEWED (v2). Branch: `feature/discovery`. Created: 2026-10-03. Revised: 2026-10-04.
Writing style: ASD-STE100 (short sentences, one idea per sentence).

Legend:
- **[src]** = statement has a source (problem statement, web, docs). `:N` = line N of `problem-statement.txt`.
- **[inferred]** = engineering judgement. No direct source.
- Confidence: **H** (high), **M** (medium), **L** (low).

---

## 0. Decision log

| # | Date | Decision |
|---|---|---|
| D1 | 2026-10-04 | Language and framework: Java 21 + Spring Boot. |
| D2 | 2026-10-04 | No payment step. Reserve is a direct booking (status `confirmed`). |
| D3 | 2026-10-04 | No user onboarding. A user is the `sub` claim of the token. |
| D4 | 2026-10-04 | Release model H1: confirm at once + owner cancel. Opt-in hold (H3) is a stretch goal. |
| D5 | 2026-10-04 | Token minting: `POST /auth/tokens` (bulk). Needs `X-Admin-Key`. Same key guards `POST /shows`. |
| D6 | 2026-10-04 | `per_user_limit` is optional in `POST /shows`. Default 4. |
| D7 | 2026-10-04 | Reserve transaction: T1 (statement-per-step). T2 is plan B. |
| D8 | 2026-10-04 | Decline layers L1 + L2 + L3: all in scope. |
| D9 | 2026-10-04 | Idempotency: store success only (option a). Retention 24 h. |
| D10 | 2026-10-04 | Error contract as §4.8. Cancel by non-owner → 404. |
| D11 | 2026-10-04 | Auth: custom JWT filter. No Spring Security. Build: Maven wrapper. |
| D12 | 2026-10-04 | `lock_timeout` maps to 500 (not 409). It is a real fault. Alert on it. |
| D13 | 2026-10-04 | Deploy F2: Render free + Neon free. Fallback F1: Oracle Always Free VM. |
| D14 | 2026-10-04 | Burst tool runs an invariant poller during the burst. |
| D15 | 2026-10-04 | Concurrency tests run twice in CI: layers on and layers off. |
| D16 | 2026-10-04 | No audit endpoint. |
| D17 | 2026-10-04 | Local runtime: Podman (WSL machine) or Docker. |
| D18 | 2026-10-04 | GitHub repo: user creates it later. Auth via `gh auth login`. Push only after approval. |
| D19 | 2026-10-04 | DB timeouts set once per pooled connection (Hikari `connection-init-sql`), not `SET LOCAL` per transaction. Same effect, one less round trip. |
| D20 | 2026-10-04 | Cancel lock order: reservation row → quota row → seat rows by label (same order as reserve). Wrong order deadlocked in 3 of 4 mutation runs. |
| D21 | 2026-10-04 | All work on one branch: `feature/seat-reservation`. |
| D22 | 2026-10-04 | L1/L2 decline only seats owned by another user (else an idempotent retry would get 409 instead of its replay). L2 maps seat → (reservation, user); cancel writes a reservation tombstone, so a late "mark sold" cannot leave a stale entry. |
| D24 | 2026-10-04 | `seats` gauge refreshes from the DB only after a write (≤1/s) plus a 30 min safety refresh. A fixed 1 s poll would keep Neon awake (186 CU-h > 100 free). |
| D25 | 2026-10-04 | Gauges are registered once and updated in place. `MultiGauge` overwrite re-registers series each refresh, so a scrape mid-refresh missed series (seen as a flaky test). |
| D26 | 2026-10-04 | Burst full profile (20k) passes all checks inside the compose network (p99 1.25 s). Through the Podman Windows port forwarder, ~7.7k connections dropped with no response; server counters prove those requests never reached the app. README tells users to run full-scale local bursts in-network. |
| D27 | 2026-10-04 | Clean-code phase: package-by-feature (common, config, auth, show, reservation, reservation.layers, observability); reservation flow split into validator, transaction, cancel service, orchestrator; ErrorCode/ReservationStatus enums; spring-javaformat enforced; V1+V2 migrations merged (nothing deployed yet). |
| D28 | 2026-10-04 | Render deploys main with `autoDeployTrigger: checksPass` (no deploy hook). `/actuator/info` shows `RENDER_GIT_COMMIT`; post-deploy job waits for it, then runs smoke burst. GHCR multi-arch image on main. |
| D29 | 2026-10-04 | Free tier measured at 0.1 CPU / 512 MB: startup 163 s plain, 120 s CDS, 57 s CDS + C1-only JIT (Java 25 AOT cache and lazy init gave no gain). Smoke burst: 30 s pool wait → 110 x 5xx; 180 s → 0 x 5xx, PASS, but ~13 req/s and 202/1500 client timeouts. Free tier is correct but cannot absorb a 20k burst quickly. |
| D30 | 2026-10-04 | User decision: Render free Postgres (30-day expiry) instead of Neon, created by the Blueprint and wired via `fromDatabase`; Neon is the fallback (DEPLOY.md §7). JDBC URL built from host/port/name when `DATABASE_URL` is unset. Render's 0.1 CPU is a shared slice, not a hard cap like the local `--cpus 0.1` test, so live capacity must be measured live. |
| D31 | 2026-10-04 | Live full burst (20k, 2,000 in flight) restarted the free instance. Local repro at 512 MB: heap 75% peaked at 97% of the limit; heap 50% died with Java OOM. Cause: every accepted request parks deep in the stack (virtual thread stacks live on the heap). Fix = R7: `ConcurrencyLimitFilter`, fair semaphore (64), requests wait at the top of the stack, nothing rejected; heap 60% on Render. Result: peak 79%, 0 timeouts, p99 19 s, 31/31 PASS. Live smoke after warm-up: 79-101 req/s, 0 5xx; first cold run had 403 edge 5xx (app itself 0). |
| D23 | 2026-10-04 | Measured (500-user hot seat, local container): DB declines 499 → 0 with layers on; server p50 7 → 2 ms, p99 272 → 191 ms. Client-bound; Phase 7 burst tool re-measures. |

---

## 1. Goal

Build, deploy, and operate one JSON HTTP service. The service sells assigned seats for a show.
The service must stay correct under a burst of about 20,000 concurrent reservations. [src: :7, :42]
Observability has the same weight as correctness. [src: :51]
The graders grade the running service, not the write-up. [src: :8]
The graders hit the live URL with their own bursts. [src: :8, :58] We do our best to survive this on the live URL.

---

## 2. Requirements extract

### 2.1 Functional requirements

| ID | Requirement | Source |
|---|---|---|
| FR-1 | `POST /shows` (admin). Body: name, seats[], price_paise, optional per_user_limit. Returns show id and all seats `available`. | :17-19, :29 |
| FR-2 | `POST /shows/{id}/reserve` (user). Identity comes from the token. Body: seats[], idempotency_key. 201 returns reservation with status `confirmed`. | :21-26 |
| FR-3 | Release model: explicit cancel (owner only) OR time-boxed hold. We choose cancel (D4). | :33-34 |
| FR-4 | `GET /shows/{id}`: per-seat status and counts. | :36-37 |
| FR-5 | Liveness, readiness, metrics endpoints. | :39, :55-56 |
| FR-6 | Money is integer paise. Never floating point. | :15, :69 |

Scope notes:
- No payment integration. The statement has no payment endpoint. The 201 already returns `confirmed` and `amount_paise`. [src: :26]
- "Never double-charge a retried request" means idempotency. It does not mean a payment gateway. [src: :7; inferred, H]
- No user onboarding. No signup endpoint. No users table. The statement only says "authenticated user" and "identity comes from the auth token". [src: :21-22]

### 2.2 Correctness bar (graded under burst)

| ID | Invariant | Source |
|---|---|---|
| C-1 | No seat is confirmed to two users. Per hot seat: exactly one 201, all others 409. | :43 |
| C-2 | Zero 5xx in the whole burst. | :44 |
| C-3 | `available + held + confirmed == total_seats`, during and after the burst. | :45 |
| C-4 | Same idempotency key = one reservation. Same key + different seats = 409. | :46 |
| C-5 | Per-user limit holds under concurrency (10 parallel requests, limit 4 → max 4). | :47 |
| C-6 | Identity is only from the token. Spoofed body fields have no effect. Cancel only own reservations. | :48 |

Under D4, "held" in C-1 and C-5 means "owned". [inferred, H]

### 2.3 Deploy & observe requirements

| ID | Requirement | Source |
|---|---|---|
| D-1 | Public URL. Must survive cold start and come up healthy. | :53 |
| D-2 | Dockerfile / compose. Clean checkout runs the same way as deploy. | :54 |
| D-3 | Liveness + readiness. Readiness checks DB and fails closed. | :55 |
| D-4 | Prometheus metrics: confirmed counter, declined-by-reason counter, seats-available gauge. Must reconcile with API state. | :56 |
| D-5 | Structured logs with correlation id. Public log access OR screen recording under load. | :57 |
| D-6 | One-command burst script with hot-seat storm. Prints outcome distribution and final reconciliation. | :58 |

### 2.4 Deliverables

| # | Deliverable | Source |
|---|---|---|
| 1 | Public Git repo, incremental commit history. | :62 |
| 2 | Live URL. | :63 |
| 3 | Burst script + run instructions in README. | :64 |
| 4 | Metrics + logs access. | :65 |
| 5 | WRITEUP.md: atomic decision, deadlock avoidance, idempotency, holds/expiry, CAP under partition, paging alerts, AI usage, next steps. | :66 |

---

## 3. Reference design (Ticketmaster breakdown)

Source: hellointerview.com Ticketmaster breakdown. [src]

We take:
- Strong consistency for booking.
- Short DB transactions. No lock held during user think time.
- "Implicit status" expiry check (for the stretch hold model). No cron needed for correctness.
- DB is the single source of truth.

We skip:
- Redis lock. Reason: second store, more failure modes.
- Virtual waiting queue. Reason: product feature. Graders expect 409, not a queue.
- Microservices, search. Reason: out of scope.

The statement encourages one DB. [src: :15]

---

## 4. Product architecture

### 4.1 Shape

```
Client / burst tool
   │ HTTPS
   ▼
Platform edge (TLS)
   ▼
Spring Boot app (one instance, no state needed for correctness)
   ├─ RequestIdFilter    → X-Request-Id into MDC
   ├─ AuthFilter         → JWT → user_id. Admin key check.
   ├─ Controllers        → shows, reserve, cancel, auth
   ├─ Decline layers     → L2 sold set → L1 read → L3 per-seat claim (§6)
   ├─ ReservationService → ONE short DB transaction per request
   ├─ Actuator           → liveness, readiness, /prometheus
   └─ HikariCP pool
   ▼
PostgreSQL = single source of truth
```

- One instance. The free tier gives one. [src: render.com/docs/free]
- The design is correct with N instances. All decisions are in the DB. L2/L3 are per-instance speed-ups only. [inferred, H]
- No cache store, no Redis, no queue.
- Virtual threads let thousands of requests wait cheaply.

### 4.2 Data model

| Table | Columns | Why |
|---|---|---|
| `shows` | `id` UUID, `name`, `price_paise BIGINT`, `per_user_limit INT DEFAULT 4`, `total_seats INT`, `created_at` | Show definition. Immutable after create. Safe to cache. |
| `seats` | PK `(show_id, label)`, `status` (AVAILABLE/HELD/CONFIRMED), `reservation_id`, `owner_user_id`, `hold_expires_at` (stretch only), `updated_at` | One row per seat. One status per seat. |
| `reservations` | `id` UUID, `show_id`, `user_id`, `seat_labels TEXT[]`, `amount_paise BIGINT`, `status` (CONFIRMED/CANCELLED), `created_at` | Reservation record. Kept after cancel. |
| `user_show_quota` | PK `(show_id, user_id)`, `seats_owned INT` | Per-user counter. Its row lock serialises one user's requests for one show. |
| `idempotency_keys` | PK `(user_id, key)`, `request_hash`, `show_id`, `reservation_id`, `response_json`, `created_at` | Exactly-once record. Scoped per user. |

Design points:
- Invariant C-3 holds by construction. Each seat is one row with one status. [inferred, H]
- Quota row, not `COUNT(*)`. Two parallel counts can both read 3 and both add 1. A count cannot lock rows that do not exist yet. A guarded update on one row is atomic. [inferred, H]
- `seats` has one row per seat. So one seat can have only one `reservation_id`. A separate unique index is not needed.
- Seat labels are free strings per show. Duplicate labels in `POST /shows` → 422.
- Seat cap per show: 10,000. [inferred, M]
- `per_user_limit` is optional in `POST /shows`. Default 4. [src: :29, :47]

### 4.3 The atomic decision

#### 4.3.1 Options

| Opt | Mechanism | Race-free? | Verdict |
|---|---|---|---|
| A | Read status, then write in app | **No.** Double-sells. [src: :49] | Reject. |
| B | Conditional `UPDATE … WHERE status='AVAILABLE'`, check row count | Yes. | Part of T2 (plan B). |
| C | `SELECT … ORDER BY label FOR UPDATE`, check, then `UPDATE` | Yes. Sorted order avoids deadlock. [src: :49] | **Chosen (T1).** |
| D | Unique partial index on active claims | Yes. | Not needed. One row per seat gives the same guarantee. |
| E | `NOWAIT` / `SKIP LOCKED` | Loser can get 409 while the holder rolls back. Then no one wins. | Reject. Breaks "exactly one 201". |
| F | Redis `SET NX EX` + DB | Yes, if DB guard stays. | Reject. Second store. |
| G | SERIALIZABLE isolation | Yes. | Reject. Serialization failures → retries → 5xx risk. |

#### 4.3.2 Lock behaviour in Postgres

- `INSERT`, `UPDATE`, `DELETE` take a `ROW EXCLUSIVE` table lock. This mode does not conflict with itself. Parallel writes do not block at table level. [src: PostgreSQL docs, Explicit Locking, 13.3.1]
- Conflicts happen only at row level.
- Two inserts with the same unique key: the second waits for the first transaction. Then it sees the conflict (commit) or succeeds (rollback). [src: PG docs; inferred, H]
- `FOR UPDATE` in READ COMMITTED: after a wait, Postgres re-reads the newest row version. [src: PG docs, Transaction Isolation 13.2.1]

#### 4.3.3 Chosen flow: T1

Before the transaction (no DB locks):
1. Verify token. Get `user_id`.
2. Validate request (show exists, seats exist, not empty, no duplicates, `n <= per_user_limit`).
3. Run decline layers L2 → L1 → L3 (§6).

Transaction (READ COMMITTED). One statement per step:

| Step | SQL (summary) | Outcome |
|---|---|---|
| 1 | `INSERT INTO idempotency_keys … ON CONFLICT (user_id, key) DO NOTHING RETURNING 1` | Row → new key, continue. No row → rollback, read stored row: same hash → replay 201; other hash → 409 `idempotency_key_reuse`. |
| 2 | `INSERT INTO user_show_quota … ON CONFLICT DO UPDATE SET seats_owned = seats_owned + :n WHERE seats_owned + :n <= :limit RETURNING seats_owned` | No row → rollback → 409 `per_user_limit`. |
| 3 | `SELECT label, status FROM seats WHERE show_id=:show AND label = ANY(:labels) ORDER BY label FOR UPDATE` | Any seat not AVAILABLE → rollback → 409 `seat_taken`. |
| 4 | `INSERT INTO reservations …` | — |
| 5 | `UPDATE seats SET status='CONFIRMED', reservation_id, owner_user_id WHERE show_id AND label = ANY(:labels)` | — |
| 6 | `UPDATE idempotency_keys SET reservation_id, response_json WHERE user_id AND key` | — |
| 7 | `COMMIT` | 201. |

Global lock order: idempotency row → quota row → seat rows by label. One order → no deadlock cycle. [inferred, H]
All-or-nothing: any seat not free → whole request declined. [inferred, H]
A rollback also removes the idempotency row. A declined key can retry (D9).

Hot seat example (500 users → A12, layers off):
- All reach step 3 and queue on row A12.
- First gets the lock → AVAILABLE → commits → 201.
- Each next one gets the lock → reads CONFIRMED → rollback → 409.
- Exactly one 201.

With layers on, L3 lets only one request per seat reach the DB (§6).

#### 4.3.4 Plan B: T2 (only if T1 fails load test)

Trigger: hot-seat p99 too high, or pool pending > 0 for a sustained time, in the live burst.

T2 merges steps 3–6 into one statement. The seat lock is held for one round trip + commit (T1: about four + commit).

```sql
WITH locked AS (
  SELECT label, status FROM seats
   WHERE show_id = :show AND label = ANY(:labels)
   ORDER BY label
   FOR UPDATE
),
ok AS (
  SELECT count(*) FILTER (WHERE status = 'AVAILABLE') = :n AS all_free FROM locked
),
claim AS (
  UPDATE seats s
     SET status = 'CONFIRMED', reservation_id = :rid, owner_user_id = :uid, updated_at = now()
    FROM ok
   WHERE ok.all_free AND s.show_id = :show AND s.label = ANY(:labels)
),
res AS (
  INSERT INTO reservations(id, show_id, user_id, seat_labels, amount_paise, status, created_at)
  SELECT :rid, :show, :uid, :labels, :amount, 'CONFIRMED', now() FROM ok WHERE ok.all_free
),
idem AS (
  UPDATE idempotency_keys SET reservation_id = :rid, response_json = :json
   WHERE user_id = :uid AND key = :key AND (SELECT all_free FROM ok)
)
SELECT all_free FROM ok;
```

- The app creates `rid`, `amount`, and the response JSON before the transaction.
- Caveat: data-modifying CTEs with lock re-checks are subtle. Prove with concurrency tests. [M until tested]
- T2a fallback: split into "lock + read" then "write all". One more round trip. Simpler.
- T3 (PL/pgSQL function, one round trip) stays a last option. Logic in SQL is harder to extend live.
- Rejected: `synchronous_commit=off`. A crash can lose a confirmed booking.

### 4.4 Partial requests

All-or-nothing. Any requested seat not free → 409 `seat_taken` for the whole request. [inferred, H]
Reason: one check in one transaction. Simple to prove. Matches user intent (group seats).

### 4.5 Release model (D4)

| Opt | Model | Status |
|---|---|---|
| H1 | Reserve → `confirmed`. Owner can `POST /reservations/{id}/cancel`. | **Chosen.** |
| H2 | Reserve → `held` + TTL. Confirm endpoint. Expiry → available. | Rejected as default. Breaks the 201 example (`:26`). Grader scripts do not know a confirm call. |
| H3 | H1 + opt-in `"hold": true` → `held` + TTL + `POST /reservations/{id}/confirm`. | **Stretch goal.** |

Why a seat is never `held` under H1:
- The spec lets us choose a model. [src: :33]
- In the cancel model, reserve goes `available → confirmed`. Cancel goes `confirmed → available`.
- `GET /shows/{id}` shows `held: 0`. The invariant stays `available + 0 + confirmed == total`.
- `HELD` stays in the status enum. This keeps H3 easy to add.

Cancel flow:
```
BEGIN
  SELECT reservation WHERE id=:rid FOR UPDATE
  - not found OR user_id ≠ token user → 404 reservation_not_found
  - already CANCELLED → 200, same body (idempotent)
  UPDATE seats SET status='AVAILABLE', owner_user_id=NULL, reservation_id=NULL
   WHERE reservation_id = :rid
  UPDATE user_show_quota SET seats_owned = seats_owned - :n
  UPDATE reservations SET status='CANCELLED'
COMMIT → 200
After commit: remove seats from L2 sold set.
```
The guard `WHERE reservation_id = :rid` makes sure a cancel never frees a seat of another reservation. [src: :34; inferred, H]

H3 expiry (stretch): implicit status. A `HELD` row with `hold_expires_at < now()` counts as available at read and at write. No cron needed for correctness. [src: Ticketmaster breakdown]

### 4.6 Idempotency (D9)

Purpose: a client retries after a lost response. Without a key, the server cannot tell a retry from a new request. The retry can book twice or fail wrongly. [src: :7, :30, :46]

- Scope: `(user_id, idempotency_key)`. Users do not collide.
- Source: header `Idempotency-Key` first, else body `idempotency_key`. Missing → 400. [src: :23]
- Request hash: SHA-256 of `show_id` + sorted seat labels.
- Same key + same hash → return stored 201. No change. Metric `declined{reason="idempotent_replay"}`.
- Same key + different hash → 409 `idempotency_key_reuse`.
- Same key in flight in parallel → the second insert waits. Then the rules above apply.
- Only success is stored. A 409 rolls back the key. The same key can retry and get a fresh check.
  - Example: user hits limit with key k1 → 409. User cancels another reservation. Retry with k1 → real check → 201.
  - Rule "same key → one reservation" still holds. Only one success can be stored per key.
- Retention: 24 h. A scheduled job deletes older rows.

### 4.7 Authentication (D3, D5, D11)

- JWT HS256 in `Authorization: Bearer`. Claims: `sub` = user_id, `exp` ≈ 1 h.
- Custom servlet filter (JJWT or Nimbus). No Spring Security.
- The service ignores any `user_id` in the body. [src: :22, :48]
- Admin credential: env var `ADMIN_API_KEY`. Header `X-Admin-Key`.
- `POST /shows` needs `X-Admin-Key`.
- `POST /auth/tokens` needs `X-Admin-Key`. Body `{"user_ids": ["u1", "u2", …]}`. Returns `{"tokens": {"u1": "jwt…", …}}`. Bulk, so the burst tool mints 20k users in a few calls.
- Live demo: README shares the demo admin key. Graders set their own key on their own deploy.
- Secrets (`JWT_SECRET`, `ADMIN_API_KEY`) come from env vars. Never in the repo. Never in logs.

### 4.8 Error contract (D10)

| Case | HTTP | `error` |
|---|---|---|
| Seat taken | 409 | `seat_taken` |
| Per-user limit | 409 | `per_user_limit` |
| Same key, different body | 409 | `idempotency_key_reuse` |
| Same key, same body | 201 (original body) | — (metric `idempotent_replay`) |
| Show not found | 404 | `show_not_found` |
| Unknown seat / empty list / duplicate labels | 422 | `invalid_seats` |
| Missing idempotency key | 400 | `idempotency_key_required` |
| Bad / missing token | 401 | `unauthenticated` |
| Wrong admin key | 403 | `forbidden` |
| Cancel of other user's reservation | 404 | `reservation_not_found` |
| Cancel of already-cancelled reservation | 200 (same body) | — |
| `lock_timeout` | 500 | `internal_error` (D12) |
| Any unknown error | 500 | `internal_error` |

Error body: `{"error": "<code>", "message": "...", "request_id": "..."}`.
Known DB errors with a domain meaning map to 4xx. `lock_timeout` stays 500. It must not happen in a healthy system.

---

## 5. Tech stack

| Area | Choice | Conf |
|---|---|---|
| Language | Java 21 LTS (virtual threads). Local JDK 21.0.6 present. | H |
| Framework | Spring Boot, latest stable at build start (check 3.5.x vs 4.0.x then). | M |
| Web | Spring MVC + `spring.threads.virtual.enabled=true`. No WebFlux. | H |
| DB access | `JdbcClient` with explicit SQL. No JPA on the reserve path. | H |
| Migrations | Flyway at startup. | H |
| DB | PostgreSQL 16/17. | H |
| Pool | HikariCP, ~10 connections (sized to Neon limits). | H |
| Auth | Custom JWT filter + admin-key filter. No Spring Security. (D11) | H |
| Metrics | Micrometer + Prometheus registry (Actuator). | H |
| Logs | Spring Boot structured JSON logging + MDC request id + async appender. | M |
| L2 / L3 | L2: concurrent set in memory. L3: per-seat in-flight claim map. | H |
| Build | Maven wrapper. (D11) | H |
| Container | Multi-stage Dockerfile, JRE 21, layered jar, AppCDS, multi-arch (amd64 + arm64). | H |
| Tests | JUnit 5, Testcontainers (Postgres), AssertJ. | H |
| Burst tool | Java CLI module (§8). | M |
| Local runtime | Podman 5.8 (WSL machine `podman-machine`) + `podman compose`, or Docker. (D17) | H |

Testcontainers on Podman (local only; CI uses Docker): [inferred, M — verify in Phase 0]
- Set `DOCKER_HOST` to the Podman machine pipe/socket.
- Set `TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk fails, or use a rootful machine.
- Fallback: run `./mvnw verify` inside WSL with the Podman socket.

Rejected: Redis, Kafka, JPA/Hibernate on the hot path, Spring Security, WebFlux.

---

## 6. Capacity and the zero-5xx risk

### 6.1 Sources of 5xx

| # | Source | Fix | Conf |
|---|---|---|---|
| R1 | Pool exhausted. Hikari timeout → 500. | L3 + L1/L2. Pool wait timeout > burst drain time. | H |
| R2 | Long lock waits on hot seat hold connections. | L3. T1 now; T2 if needed. | H |
| R3 | Platform edge 502/503/504. Outside our code. | Fast responses. L1–L3. README capacity note. Fallback F1. | M |
| R4 | Out of memory (512 MB). | `-XX:MaxRAMPercentage=75`. Virtual threads. Request size cap. Bounded L2/L3 maps. | M |
| R5 | Slow cold start. | AppCDS. Layered jar. Readiness after Flyway. | M |
| R6 | Known DB errors (unique violation, serialization). | Map to 4xx with reason. | H |
| R7 | Too much total concurrency. | Fair semaphore in front of DB work. Queue, never shed. No 503/429 (graders expect 409). | M |

DB timeouts (set once per pooled connection, D19):
- `lock_timeout` ≈ 5 s. Safety net. Maps to 500 (D12). Alert on it.
- `statement_timeout` ≈ 10 s.

### 6.2 Decline layers (D8)

The DB stays the only authority. Layers only decline faster. They never grant a seat.

| Layer | How | Helps with | Limit |
|---|---|---|---|
| L2 sold set | In-memory set of `(show, seat)`. Add after commit. Remove after cancel commit. Hit → 409, no DB. | Requests after the winner commits. | Per instance. Not needed for correctness. |
| L1 plain read | Non-locking `SELECT status`. Any seat CONFIRMED → 409. MVCC read never blocks. | Same, when L2 misses (other instance, restart). | First wave sees AVAILABLE and goes on. |
| L3 per-seat claim | In-app per-seat lock. One request per seat goes to the DB. Others wait on virtual threads. Winner commits → waiters get 409. Winner rolls back → next waiter tries. | The first wave. 500 hot requests use 1 DB connection, not 500. Protects the pool (R1, R2). | Per instance. With N instances the DB still serialises. |

Rules:
- L1 decline is safe. A stale "taken" is a valid race outcome.
- L3 multi-seat requests take claims in sorted label order. No in-app deadlock.
- L3 has a timeout. On timeout, the waiter goes to the DB path. Never a 5xx from L3.
- All layers have a config flag. Tests run with layers on and off (D15).

Flow:
```
request → auth → validate → L2 sold set? ──hit──► 409 (no DB)
                              │miss
                              ▼
                          L1 plain read: any seat CONFIRMED? ──yes──► 409 (no lock)
                              │no
                              ▼
                          L3 per-seat claim (sorted) ── waiting → winner committed → 409
                              │got the turn
                              ▼
                          T1 transaction
                              │commit OK → add seats to L2, release L3, waiters → 409
                              ▼
                             201
```

### 6.3 Proof

Run the burst tool against local compose with free-tier limits (`cpus: 0.1`, `mem_limit: 512m`). Then against the live URL.
Record p50/p99, status distribution, pool usage, invariant check.

---

## 7. Deployment (D13)

### 7.1 Platform facts (Oct 2026)

| Platform | Free tier | Compute | Cold start | Postgres | Source |
|---|---|---|---|---|---|
| Render | Yes. 750 h/month. Sleeps after 15 min idle. | Free: 512 MB, ~0.1 CPU [inferred]. Starter $7: 0.5 CPU. | ~60 s | Free PG 1 GB, expires after 30 days. | render.com/docs/free, dev.to |
| Neon (DB) | Yes. | 0.25 CU (0.25 vCPU, 1 GB). max_connections 112. 100 CU-h/month. Sleeps after 5 min. | sub-second wake [inferred] | 0.5 GB. | neon.com FAQ |
| Oracle Always Free | Yes. | A1: 2 OCPU / 12 GB (new accounts). | None | Self-host. | search (M) |
| Railway | No ($5 trial). | Usage-based. | None | Same project. | dev.to |
| Fly.io | No. | ~$2/mo smallest. | 200–500 ms | Paid. | dev.to |
| Koyeb | Yes. | 0.1 vCPU / 512 MB. | Yes | 5 compute-h/month. | agentdeals.dev |

### 7.2 Primary: F2 = Render free + Neon free

- Region: Singapore for both (Render Singapore + Neon `ap-southeast-1`). Closest region to graders. Low app ↔ DB latency. [inferred, M — check region lists at deploy]
- Neon: use the direct endpoint. Small Hikari pool (~10). The pooled endpoint (PgBouncer transaction mode) can break prepared statements and session settings. [inferred, M]
- Timeouts set per transaction with `SET LOCAL`. Works on either endpoint.
- Render health check path: readiness.
- Render builds from our Dockerfile. Auto-deploy off. CI calls a deploy hook after green build (§10).
- Keep-alive: UptimeRobot (free, 5 min) pings **liveness only**. App stays awake. Neon can sleep. Reason: always-on Neon = 0.25 CU × 744 h = 186 CU-h > 100 free.
- Cold start budget: ~60 s (Render wake + JVM start + Neon wake). README says so.

### 7.3 Fallback: F1 = Oracle Always Free VM

Trigger: live burst (full profile) shows edge 5xx or invariant failure after tuning.
- One VM. `docker compose` with app, Postgres, Prometheus, Grafana, Caddy (TLS).
- Domain via sslip.io or DuckDNS.
- ARM (Ampere) → multi-arch image (built in CI from the start).
- User actions needed: Oracle account (card check), A1 VM, open ports 80/443, SSH access.
- Risk: "out of capacity" on VM create. Signup friction.

### 7.4 Cold start and health

- Readiness = DB ping (`SELECT 1`) + Flyway done. Returns 503 when DB is down. [src: :55]
- Liveness = process up. No DB check. Avoids restart loops when DB is down. [inferred, H]
- Flyway on startup. Idempotent. A fresh DB comes up empty and correct.

---

## 8. Burst tool (D-6, D14)

Java CLI module `burst/` (HttpClient + virtual threads).

Run:
- `./burst.sh <BASE_URL> <ADMIN_KEY> [--profile smoke|full]`
- `make burst URL=… KEY=…`
- `burst.sh` detects `docker` or `podman` to run in a container. Else it runs `java -jar`.

Profiles:

| Profile | Requests | Use |
|---|---|---|
| `smoke` | ~1–2k | Free-tier live URL. Post-deploy CI check. |
| `full` | ~20k | Local compose. Graders' own deploy. |

Scenarios (fresh show each run):
1. Setup: create show (e.g. 1,000 seats, limit 4). Mint tokens in bulk for N users.
2. Hot-seat storm: K hot seats × 500 users. Expect exactly 1×201 per seat. Rest 409 `seat_taken`.
3. Stampede: remaining requests over all seats. Skew to "good" rows (A–C).
4. Idempotent retry: one key ×50 in parallel → 1 reservation. Same key + other seats → 409 `idempotency_key_reuse`.
5. Per-user limit: one user, 10 parallel reserves, limit 4 → ≤4 seats.
6. Spoof: body `user_id` of another user → acts as token user. Cancel of other user's reservation → 404.
7. Cancel → rebook: cancel one reservation. Another user books that seat → 201.
8. Final reconciliation:
   - `GET /shows/{id}`: `available + held + confirmed == total`.
   - Each 201 seat appears once, with the right owner.
   - Scrape `/prometheus`. Confirmed counter == confirmed seats. Declined counts == client-seen 409s.

Invariant poller (D14): during the stampede, poll `GET /shows/{id}` every 200 ms. Check the invariant. Report any breach with a timestamp.
Requirement on the API: counts and seat list come from **one query, one snapshot**. [inferred, H]

Output: table per scenario (201 / 409 by reason / other 4xx / **5xx** / client errors), p50/p95/p99, PASS/FAIL per invariant. Exit code ≠ 0 on any failure.

README: capacity note for the free tier, and "run at full scale" with local compose. (U5)

---

## 9. Test strategy (D15)

| Layer | Tests | Tool |
|---|---|---|
| Unit | Request hash (order-free). Seat validation. Money (`long` paise, overflow guard). JWT verify, expiry, bad signature. Exception → HTTP map (`lock_timeout` → 500). | JUnit 5, AssertJ |
| L2/L3 unit | Sold set add on commit, remove on cancel. One claim per seat. Waiters released on commit/rollback. Sorted multi-seat claims. Timeout path. | JUnit + threads |
| Integration | All endpoints on real Postgres. Full error contract (§4.8). Flyway on empty DB. | Testcontainers + Spring Boot test |
| Concurrency | 500 threads → 1 seat → 1×201, 499×409, zero 5xx. One user, 10 parallel, limit 4 → ≤4. Same key ×50 → 1 reservation. Overlapping multi-seat ([A1,A2] vs [A2,A1]) → no deadlock, all-or-nothing. Cancel vs rebook race → no resurrection. Invariant + "no seat with two owners" after each. | Testcontainers, `ExecutorService` + `CountDownLatch` |
| Concurrency, layers off | Same suite with L1/L2/L3 disabled. Proves the DB alone is race-free. | Same |
| Readiness | Stop Postgres → readiness 503, liveness 200. Start → readiness 200. | Testcontainers |
| Metrics | After a concurrency test: counters match DB; gauge matches API. | Integration |
| Security | Body `user_id` ignored. No token → 401. User token on admin endpoints → 403. Cancel of other's → 404. | Integration |
| Load / E2E | `full` on local compose with free-tier limits. `smoke` on live URL. | burst.sh |

Coverage goal: every invariant C-1..C-6 and every error-contract row has at least one test. No % target. [inferred, H]

---

## 10. CI/CD

```
push / PR (any branch)
  └─ CI
       ├─ ./mvnw verify   (unit + integration + concurrency, layers on and off)
       ├─ docker buildx --platform linux/amd64,linux/arm64
       └─ upload test reports

merge to main
  ├─ CI (as above)
  ├─ push image → GHCR (:sha, :latest)
  ├─ call Render deploy hook (only after CI green)
  └─ post-deploy
       ├─ poll /health/readiness until 200 (timeout ~3 min)
       ├─ burst --profile smoke against live URL
       └─ fail on any 5xx or invariant breach
```

| Item | Choice | Conf |
|---|---|---|
| CI host | GitHub Actions. Docker available for Testcontainers. | H |
| Deploy trigger | Render auto-deploy off. Deploy hook called by Actions after green CI. (Check if Render has a native "after CI checks pass" option.) | M |
| Image | Render builds from Dockerfile. CI also pushes multi-arch image to GHCR (ready for F1). | M |
| Secrets | Render env: `JWT_SECRET`, `ADMIN_API_KEY`, `DATABASE_URL`. GitHub secrets: `RENDER_DEPLOY_HOOK`, `ADMIN_API_KEY`. | H |
| Branching | Protected `main`. Feature branch → PR → merge. | H |
| Commits | Small Conventional Commits. One logical step each. [src: :62] | H |
| Migrations | Flyway at app start. | H |
| Rollback | Render manual rollback, or re-run hook with old commit. | M |
| Repo access | User creates the GitHub repo. Auth with `gh auth login` (token never pasted in chat). Push only after approval. (D18) | H |

---

## 11. Observability

### 11.1 Metrics (`/actuator/prometheus`, public)

| Metric | Type | Labels | Source |
|---|---|---|---|
| `reservations_confirmed_total` | counter | `show_id` | After commit |
| `reservations_declined_total` | counter | `show_id`, `reason` = seat_taken / per_user_limit / idempotent_replay / idempotency_key_reuse / invalid | At decision point |
| `reservations_decline_path_total` | counter | `layer` = l2_sold_set / l1_read / l3_waiter / db | Proves layers work |
| `reservations_cancelled_total` | counter | `show_id` | After commit |
| `seats` | gauge | `show_id`, `state` = available / held / confirmed | Read from DB (one query, cached ~1 s) |
| `seat_claim_inflight` | gauge | — | L3 |
| `http_server_requests_seconds` | histogram | uri, method, status | Built-in |
| `hikaricp_connections_active/pending` | gauge | pool | Built-in |
| `jvm_*`, `process_*` | — | — | Built-in |

- Gauges read from the DB. They always match `GET /shows/{id}`. [inferred, H]
- Counters live in memory. They reset on restart. WRITEUP says so. The burst checks counters against the API in the same run.
- `show_id` label is fine for a few shows. Cap or drop in production. [inferred, M]

### 11.2 Logs

- JSON to stdout. Fields: `ts`, `level`, `request_id`, `user_id`, `show_id`, `route`, `status`, `outcome`, `reason`, `seats`, `latency_ms`.
- `X-Request-Id`: accept or create. Put in MDC. Return in response header and error body.
- One access line per request. One decision line per reserve. No per-step logs on the hot path.
- Async appender, so logging does not slow the burst. [inferred, M]
- Never log tokens or the admin key.

### 11.3 Hosting (F2)

| Need | Tool |
|---|---|
| Metrics store + dashboard | Grafana Cloud free. "Metrics Endpoint" integration scrapes public `/actuator/prometheus`. |
| Public dashboard | Grafana Cloud public dashboard link in README. |
| Logs | Grafana Cloud Loki via Logback appender (`loki4j`). Render free has no sidecar. |
| Public log access | Loki panel on the public dashboard, AND a screen recording under load (backup). [src: :57] |
| Local | Compose adds Prometheus + Grafana with the same dashboard JSON. |

[inferred, M] Check Grafana Cloud free limits and public dashboard support at setup.

### 11.4 Alerts (what pages at 2 am)

| Alert | Condition |
|---|---|
| Service down | Readiness failing > 1 min |
| Any 5xx | `rate(http_server_requests_seconds_count{status=~"5.."}) > 0` for 1 min |
| Invariant breach | `sum(seats) by show != total_seats` |
| Lock timeout | Any 500 caused by `lock_timeout` (D12) |
| Pool saturation | `hikaricp_connections_pending > 0` for 2 min |
| Latency | Reserve p99 > 1 s for 5 min |
| Counter/gauge drift | Confirmed counter ≠ confirmed gauge outside a restart window |

### 11.5 Dashboard panels

Request rate by status. Confirmed vs declined by reason (stacked). Decline path by layer. Seats by state (stacked, flat total). p50/p95/p99. Pool active/pending. L3 in-flight. JVM heap/CPU. Logs panel filtered by `request_id`.

No audit endpoint (D16). The invariant check lives in the burst reconciliation, the poller, and the `seats` gauge alert.

---

## 12. Deliverable → delivery map

| Deliverable | How | Where |
|---|---|---|
| Public repo + history | GitHub (D18). Feature branch → PR → `main`. Small Conventional Commits. | GitHub |
| Live URL | Render + Neon (F2). Fallback F1. | README top |
| Burst script | `./burst.sh <URL> <ADMIN_KEY> --profile smoke\|full`, `make burst` | `burst/`, README |
| Metrics | Public `/actuator/prometheus` + Grafana Cloud public dashboard | README |
| Logs | Grafana Cloud Loki panel + screen recording | README |
| WRITEUP.md | All sections from `:66` + honest AI usage | repo root |
| Docker | `Dockerfile` (multi-arch) + `docker-compose.yml` (app, postgres, prometheus, grafana) | repo root |
| README notes | Free-tier capacity note. Run at full scale. ~60 s cold start. Token minting. Docker and Podman commands. | README |

---

## 13. Build phases

| # | Phase | Output |
|---|---|---|
| 0 | Skeleton | Maven, Boot app, Dockerfile, compose, Flyway, health, CI, JSON logs + request id, Testcontainers on Podman verified |
| 1 | Shows | `POST /shows` (admin key), `GET /shows/{id}` (one-snapshot counts), schema |
| 2 | Auth | JWT filter, `POST /auth/tokens` (bulk, admin key) |
| 3 | Reserve core | T1 transaction. Error contract. Concurrency tests (layers off) |
| 4 | Cancel | Owner-only, idempotent, quota decrement, tests |
| 5 | L1/L2/L3 | Decline layers + flags. Concurrency tests with layers on |
| 6 | Observability | Metrics, async logs, dashboard JSON, local Prometheus/Grafana |
| 7 | Burst tool | Scenarios 1–8, poller, profiles, report, exit code |
| 8 | Deploy | Render + Neon, deploy hook, post-deploy smoke, Grafana Cloud, UptimeRobot |
| 9 | Tune | Live burst. Decide T2 / F1 if needed |
| 10 | Docs | README, WRITEUP.md, screen recording |
| Stretch | Hold model (H3) | Opt-in hold + confirm + implicit expiry |

---

## 14. Open questions

| ID | Question | Status |
|---|---|---|
| Q1 | Hold model | Closed: H1 + H3 stretch (D4) |
| Q2 | Store 409 against key? | Closed: success only (D9) |
| Q3 | Token minting | Closed: admin-key bulk (D5) |
| Q4 | Platform | Closed: F2, fallback F1 (D13) |
| Q5 | GitHub account / repo | **Open.** User creates repo later (D18) |
| Q6 | Per-user limit | Closed: optional, default 4 (D6) |

---

## 15. Risks

| Risk | Mitigation |
|---|---|
| Edge 5xx on free tier under burst | L1–L3. Tuning. README capacity note. Fallback F1. |
| Hot-seat lock time with T1 | L3. T2 as plan B. |
| Cold start ~60 s | AppCDS. Readiness gate. UptimeRobot on liveness. README note. |
| Neon compute-hour limit | Ping liveness only. Neon sleeps when idle. |
| L2/L3 bug hides a DB fault | Concurrency suite also runs with layers off. |
| Counters reset on restart | Gauges from DB. Documented. |
| Grafana Cloud limits / no public dashboard | Screen recording backup. Local compose dashboard. |
| Testcontainers on Podman unstable | Run tests in WSL. CI uses Docker. |
| Render free Postgres expiry | Not used. Neon instead. |

---

## 16. Sources

- Problem statement: `problem-statement.txt`
- [Hello Interview — Ticketmaster breakdown](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster)
- [PostgreSQL docs — Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [PostgreSQL docs — Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [Render free tier docs](https://render.com/docs/free)
- [Render vs Railway vs Fly.io pricing 2026 (dev.to)](https://dev.to/pavel-hostim/render-vs-railway-vs-flyio-pricing-compared-2026-2e5p)
- [Railway vs Render vs Fly.io benchmarks (techsy.io)](https://techsy.io/en/blog/railway-vs-render-vs-fly-io)
- [Render vs Railway vs Fly.io (tech-insider.org)](https://tech-insider.org/render-vs-railway-vs-fly-io-2026/)
- [Koyeb free tier (agentdeals.dev)](https://agentdeals.dev/vendor/koyeb)
- [Neon free tier FAQ](https://neon.com/faqs/managed-postgres-databases-free-tier)
- [Oracle Always Free A1 (cnx-software)](https://cnx-software.com/?p=90856)
