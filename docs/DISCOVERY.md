# Discovery — Seat Reservation at Scale

Status: DRAFT for review. Branch: `feature/discovery`. Date: 2026-10-03.
Writing style: ASD-STE100 (short sentences, one idea per sentence).

Legend:
- **[src]** = statement has a source (problem statement, web, docs).
- **[inferred]** = statement is my engineering judgement. No direct source.
- Confidence: **H** (high), **M** (medium), **L** (low).

---

## 1. Goal

Build, deploy, and operate one JSON HTTP service. The service sells assigned seats for a show.
The service must stay correct under a burst of about 20,000 concurrent reservations. [src: problem-statement.txt:7,42]
Observability is equally weighted with correctness. [src: problem-statement.txt:51]
The graders grade the running service, not the write-up. [src: problem-statement.txt:8]

---

## 2. Requirements extract

### 2.1 Functional requirements

| ID | Requirement | Source |
|---|---|---|
| FR-1 | `POST /shows` (admin). Body: name, seats[], price_paise. Returns show id and all seats `available`. | :17-19 |
| FR-2 | `POST /shows/{id}/reserve` (user). Identity comes from the token. Body: seats[], idempotency_key. 201 returns reservation. | :21-26 |
| FR-3 | Release model: explicit cancel (owner only) OR time-boxed hold with auto-expiry. | :33-34 |
| FR-4 | `GET /shows/{id}`: per-seat status and counts. | :36-37 |
| FR-5 | Liveness, readiness, metrics endpoints. | :39, :55-56 |
| FR-6 | Money is integer paise. Never floating point. | :15, :69 |

### 2.2 Correctness bar (graded under burst)

| ID | Invariant | Source |
|---|---|---|
| C-1 | No seat is confirmed to two users. Per hot seat: exactly one 201, all others 409. | :43 |
| C-2 | Zero 5xx in the whole burst. | :44 |
| C-3 | `available + held + confirmed == total_seats`, during and after the burst. | :45 |
| C-4 | Same idempotency key = one reservation. Same key + different seats = 409. | :46 |
| C-5 | Per-user limit holds under concurrency (10 parallel requests, limit 4 → max 4). | :47 |
| C-6 | Identity is only from the token. Spoofed body fields have no effect. Cancel only own holds. | :48 |

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
Relevant points:
- Booking path needs strong consistency. Read path can prefer availability.
- Long `SELECT … FOR UPDATE` held over user checkout time is bad. Long transactions cause lock contention.
- "Implicit status" pattern is good: one transaction updates a seat only if it is `AVAILABLE` or `HELD` with an expired timestamp. No cron is needed for correctness.
- Redis lock with TTL is an option. The DB still keeps the final guard. This adds a second store.
- Virtual waiting queue protects the system for very hot events.
- Payment webhooks use idempotency keys.

What we take: implicit-status expiry, short DB transactions, DB as single source of truth.
What we do not take (now): Redis, waiting queue, microservices, search. Reason: scope is one service and one DB. The problem statement encourages one DB. [src: :15]

---

## 4. Product architecture

### 4.1 Shape

One stateless Spring Boot service. One PostgreSQL database. [inferred, H]

```
Client / burst tool
      │ HTTPS
      ▼
Platform edge proxy (TLS)
      ▼
Spring Boot app (Java, virtual threads)
  ├─ Auth filter (JWT → user_id, role)
  ├─ RequestId filter (X-Request-Id → MDC)
  ├─ ShowController / ReservationController
  ├─ ReservationService  (one short DB transaction per request)
  ├─ Actuator: /health/liveness, /health/readiness, /prometheus
  └─ HikariCP pool
      ▼
PostgreSQL (source of truth for seats, reservations, idempotency keys, quotas)
```

### 4.2 Data model (draft)

| Table | Key columns | Purpose |
|---|---|---|
| `shows` | id, name, price_paise BIGINT, per_user_limit INT DEFAULT 4, total_seats | Show definition. |
| `seats` | (show_id, label) PK, status, reservation_id, holder_user_id, hold_expires_at, version | One row per seat. Seat status lives in exactly one row. |
| `reservations` | id, show_id, user_id, seats[], amount_paise BIGINT, status, created_at | Reservation record. |
| `user_show_quota` | (show_id, user_id) PK, seats_held INT | Per-user counter. Serialises one user's requests for one show. |
| `idempotency_keys` | (user_id, key) PK, request_hash, reservation_id, response_code, response_body | Exactly-once record. Scoped per user. |

Invariant C-3 holds by construction: each seat is one row with exactly one status. [inferred, H]
Counts come from `GROUP BY status` on `seats`. Expired holds count as `available` at read time.

### 4.3 The atomic decision (core of the task)

Options:

| Opt | Mechanism | Race-free? | Hot-seat behaviour | Verdict |
|---|---|---|---|---|
| A | Read status, then write (app-level check) | **No.** Double-sells. [src: :49] | — | Reject. |
| B | One conditional `UPDATE seats SET status='CONFIRMED' … WHERE status='AVAILABLE' (OR expired hold)`; check updated row count | Yes. Row lock + re-check of `WHERE` after lock wait (Postgres READ COMMITTED re-evaluates). [inferred, H] | Losers wait for lock, then see 0 rows → 409. | **Recommended** for single seat. |
| C | `SELECT … FOR UPDATE` on requested seats `ORDER BY label`, validate, then `UPDATE` | Yes. Deterministic lock order avoids deadlock. [src: :49] | Same as B. | **Recommended** for multi-seat. Combine with B. |
| D | Unique partial index on active seat claims (`reservation_seats(show_id, label) WHERE active`) | Yes. DB rejects second claim. | Loser gets unique-violation → map to 409. | **Add as safety net** (defence in depth). |
| E | `FOR UPDATE NOWAIT` / `SKIP LOCKED` | Yes, but loser can get 409 while the lock holder later rolls back. Then the seat stays free and nobody wins. | Fast fail. | Reject. Can break "exactly one 201". [inferred, M] |
| F | Redis `SET NX EX` lock + DB | Yes if DB guard stays. | Fast. | Reject now. Second store, more failure modes. |
| G | SERIALIZABLE isolation | Yes. | Many serialization failures → retries → risk of 5xx. | Reject. |

Recommended flow for one reserve request (all in ONE short transaction): [inferred, M — validate with tests]

1. Insert idempotency row `(user_id, key, request_hash)` with `ON CONFLICT DO NOTHING`. If a parallel request holds the same key, Postgres makes this insert wait until that transaction ends. Then we read the stored result.
2. Lock the quota row `user_show_quota (show_id, user_id)` with a conditional update: `seats_held = seats_held + n WHERE seats_held + n <= per_user_limit`. 0 rows → 409 `per_user_limit`.
3. Lock seat rows: `SELECT … FROM seats WHERE show_id=? AND label = ANY(?) ORDER BY label FOR UPDATE`.
4. Check every seat is available (or expired hold). If not → rollback → 409 `seat_taken`.
5. Update seats, insert reservation, update idempotency row with response. Commit.

Lock order is always: idempotency key → quota row → seats by label. One global order means no deadlock cycle. [inferred, H]

### 4.4 Partial requests

Options: all-or-nothing OR best-effort.
Recommendation: **all-or-nothing.** [inferred, H]
Reason: one seat check inside one transaction. Simple to prove. Matches user intent (people buy seats together).

### 4.5 Hold / release model

Conflict found: the 201 example returns `"status": "confirmed"` [src: :26]. But C-5 and FR-3 talk about "held" seats and expiry. [src: :33, :47]

| Opt | Model | Pros | Cons |
|---|---|---|---|
| H1 | Reserve → `confirmed` at once. Owner can `POST /reservations/{id}/cancel`. No expiry. | Matches 201 example exactly. Simplest. | `held` count is always 0. |
| H2 | Reserve → `held` with TTL. `POST /reservations/{id}/confirm` → `confirmed`. Expired hold = available (implicit status). | Real ticketing flow. Uses all three states. | 201 body says `held`, not `confirmed`. Deviates from example. |
| H3 | H1 + H2: reserve returns `confirmed` (default). Optional `"hold": true` gives a TTL hold + confirm endpoint. Cancel works for both. | Matches example. Shows expiry logic. Good base for live extension in interview. | More code and tests. |

Recommendation: **H3 if time allows, else H1.** [inferred, M] → **Open question Q1.**

Release safety: cancel is a conditional update `WHERE reservation_id = ? AND holder_user_id = :tokenUser AND status IN (...)`. It cannot touch a seat that now belongs to another reservation. [inferred, H]

### 4.6 Idempotency

- Key scope: `(user_id, idempotency_key)`. Two users with the same key do not collide. [inferred, H]
- Key source: header `Idempotency-Key` first, else body `idempotency_key`. [src: :23 allows both]
- Request hash: SHA-256 of `show_id` + sorted seats. Different hash on same key → 409 `idempotency_key_reuse`. [src: :30]
- Replay: same hash → return the stored original response (same reservation_id). Count metric `declined{reason="idempotent_replay"}`. [src: :56]
- Open point: store declines (409) as final results, or let a retry try again? Recommendation: store only success; a declined key can be retried. [inferred, M] → **Open question Q2.**
- Retention: keep keys for 24 h. Clean up with a scheduled job. [inferred, M]

### 4.7 Authentication

- JWT (HS256) in `Authorization: Bearer`. Claims: `sub` = user_id, `role` = `user|admin`. [inferred, H]
- The service ignores any `user_id` in the body. [src: :22, :48]
- Graders must create tokens for thousands of users. So we need a token endpoint: `POST /auth/token {"user_id": "...", "role": "user"}`. Admin role needs an admin secret. [inferred, M] → **Open question Q3.**

### 4.8 Error contract

| Case | HTTP | `reason` |
|---|---|---|
| Seat taken / held by other | 409 | `seat_taken` |
| Per-user limit | 409 | `per_user_limit` |
| Same key, different body | 409 | `idempotency_key_reuse` |
| Replay of same key | 201 (original body) | metric `idempotent_replay` |
| Unknown seat / show | 404 / 422 | `unknown_seat` / `show_not_found` |
| Bad / missing token | 401 | `unauthenticated` |
| Cancel by non-owner | 403 or 404 | `not_owner` |
| DB lock timeout / pool exhausted | must NOT be 5xx under normal burst | see §6 |

All DB errors with a known domain meaning (unique violation, lock timeout on a seat) map to 4xx. [inferred, H]

---

## 5. Tech stack

User decision: Java + Spring Boot. [src: user]

| Area | Choice | Reason | Conf |
|---|---|---|---|
| Language | Java 21 LTS | Virtual threads. Mature images. | H |
| Framework | Spring Boot (latest stable at build time, 3.5.x or 4.0.x) | User choice. Verify version at build start. | M [inferred] |
| Web | Spring MVC + virtual threads (`spring.threads.virtual.enabled=true`) | Simple blocking code. High concurrency. WebFlux not needed. | H |
| DB access | `JdbcTemplate` / `JdbcClient` with explicit SQL | We need exact control of locking SQL. JPA hides it. | H |
| Migrations | Flyway | Schema in repo. Runs at startup. | H |
| DB | PostgreSQL 16/17 | Row locks, `ON CONFLICT`, partial unique index. | H |
| Pool | HikariCP | Default. Size tuned to DB connection limit. | H |
| Auth | Spring Security resource server (JWT) or small custom filter | Custom filter is lighter. Decide at build. | M |
| Metrics | Micrometer + Prometheus registry (Actuator) | Standard. | H |
| Logs | Spring Boot structured logging (JSON, ECS/Logstash format) + MDC request id | Built-in since Boot 3.4. | M [inferred] |
| Build | Maven (wrapper) | Common. Wrapper = clean checkout builds. | H |
| Container | Multi-stage Dockerfile, JRE 21 base, layered jar | Small image. Fast start. | H |
| Tests | JUnit 5, Testcontainers (Postgres), AssertJ | Real Postgres for lock behaviour. | H |
| Burst tool | See §8 | — | M |

Rejected: Redis (second store), Kafka (no async need), JPA/Hibernate for the hot path (hidden SQL, optimistic retries cause noise). [inferred]

---

## 6. Capacity and the "zero 5xx" risk

This is the biggest risk. [inferred, H]

- 20,000 concurrent requests hit one small instance.
- Each reserve = one short transaction (~few ms DB time).
- Hot seat: 500 requests queue on one row lock. Each waits for the previous. With short transactions this is fine.
- Danger points that create 5xx:
  1. Hikari pool timeout → exception → 500.
  2. Platform proxy timeout or connection limit → 502/503/504 from the edge (outside our code).
  3. Out-of-memory on a 512 MB container.
  4. Statement / lock timeout.

Mitigations: [inferred, M]
- Bounded concurrency: a semaphore in front of the DB work. Requests wait in a fair queue. Pool wait time > burst drain time.
- Fast decline path: if a seat is already `CONFIRMED`, decline before taking a write lock (safe: a confirmed seat cannot become available except by cancel; a stale "taken" answer is still a valid 409).
- Never return 503 for overload. If we must shed, use 429 + `Retry-After` (4xx). But 429 breaks "everyone else 409" for hot seats. So queue, do not shed.
- Global exception handler: map known DB errors to 4xx. Unknown errors stay 500 (we want to see real bugs).
- JVM flags for small containers: `-XX:MaxRAMPercentage=75`, Serial/G1 GC, CDS / AppCDS to cut start time.
- Index on `seats(show_id, label)` (PK). Keep transactions short. No remote calls inside a transaction.
- App and DB in the same region (network round trip dominates).

Free tiers with 0.1 vCPU may not survive 20k concurrent requests without edge 5xx. [inferred, M] → drives §7 choice.

---

## 7. Deployment strategy

### 7.1 Platform facts (Oct 2026)

| Platform | Free tier | Compute | Cold start | Postgres | Source |
|---|---|---|---|---|---|
| Render | Yes. 750 h/month. Sleeps after 15 min idle. | Free: 512 MB, ~0.1 CPU [inferred]. Starter $7/mo: 0.5 CPU, 512 MB. | ~60 s | Free Postgres 1 GB, **expires after 30 days**. | [src: render.com/docs/free, dev.to] |
| Railway | No. $5 one-time trial credit. Paid ~$5–15/mo. | Usage-based. Always on. | None (always on) | Same-project Postgres, private network. | [src: dev.to, techsy.io] |
| Fly.io | No free tier for new accounts. | shared-cpu-1x 256 MB ≈ $2/mo; RAM ≈ $5/GB/mo. | ~200–500 ms (machine resume) | Managed Postgres paid. | [src: dev.to, tech-insider] |
| Koyeb | Yes. 1 service. | 512 MB, 0.1 vCPU. Scale to zero after 1 h. | Yes | Free PG: 1 GB, 5 compute-hours/month only. | [src: agentdeals.dev] |
| Neon (DB only) | Yes. | 0.25 CU (0.25 vCPU, 1 GB). max_connections 112. 100 CU-h/month. Sleeps after 5 min. | ~sub-second wake [inferred] | 0.5 GB storage. Pooled endpoint (PgBouncer). | [src: neon.com FAQ, srvrlss.io] |
| Oracle Cloud Always Free (VM) | Yes. | Ampere A1: 2 OCPU / 12 GB for new accounts (was 4/24). | None (always on) | Self-host in Docker. | [src: search, conflicting reports — M] |

### 7.2 Options

| Opt | Setup | Cost | Burst survival | Cold start story | Risk |
|---|---|---|---|---|---|
| P1 | Render free web (Docker) + Neon free Postgres | $0 | **Low–Med.** 0.1 CPU JVM. | Render ~60 s wake. Neon wake. Both must pass readiness. | Burst 5xx at edge. Neon CU-hours if DB never sleeps. |
| P2 | Render Starter ($7) + Neon free | ~$7/mo | Med. 0.5 CPU. | No sleep. Restart = cold start. | Cross-provider latency. Pick same region. |
| P3 | Railway Hobby: app + Postgres in one project | ~$5/mo | **Med–High.** More CPU. Private network to DB. | Always on. Redeploy = cold start test. | Trial credit runs out. Needs card. |
| P4 | Oracle Always Free VM + docker compose (app, Postgres, Prometheus, Grafana, Caddy TLS) | $0 | **High.** 2 OCPU / 12 GB, all local. | Always on. `restart: always`. | Signup friction, card check, "out of capacity" errors. More ops (TLS, firewall, domain via sslip.io). |

Recommendation: [inferred, M]
- **Primary: P3 (Railway)** if a small paid amount is OK. Best balance of burst survival and low ops effort.
- **Free fallback: P4 (Oracle VM)** if cost must be $0. Strongest burst capacity. Also gives public Grafana for metrics and logs. More setup.
- Avoid P1 as the graded target. Render free Postgres expires in 30 days. Grading date is unknown.

→ **Open question Q4** (budget / card OK?).

### 7.3 Cold start requirement

- Readiness = DB ping (`SELECT 1`) + Flyway done. Fail closed (HTTP 503 on readiness only; this is a health endpoint, not part of the burst). [src: :55]
- Liveness = process up. No DB check (avoid restart loops when DB is down). [inferred, H]
- Platform health check points at readiness. Traffic starts only after ready.
- Flyway runs on start. Idempotent. A fresh DB comes up empty and correct.
- If the platform sleeps (P1/P2): external pinger (UptimeRobot free, 5 min) on **liveness** only. Reason: pinging readiness keeps Neon awake and burns CU-hours (0.25 CU × 744 h = 186 CU-h > 100 free). [inferred, M]

---

## 8. Burst script (D-6)

Needs: thousands of concurrent requests, many users, hot-seat storm, idempotent retries, same-key-different-body, per-user-limit storm, spoof attempt, final reconciliation.

| Opt | Tool | Pros | Cons |
|---|---|---|---|
| B1 | k6 (via `docker run grafana/k6`) | Industry standard. Good reports. | 20k VUs on a laptop is heavy. Custom reconciliation in JS. |
| B2 | Java CLI module (`burst/`), `HttpClient` + virtual threads, run via `./burst.sh <BASE_URL>` (Docker or `java -jar`) | Same language. 20k concurrent is cheap with virtual threads. Full control of scenarios and output. | We write the reporting. |
| B3 | Python asyncio + httpx | Short code. | Needs Python env. Extra language. |

Recommendation: **B2**, wrapped by `burst.sh` and `make burst`. [inferred, M]

Scenarios (each prints counts by status and reason):
1. Create fresh show (N seats).
2. Hot-seat storm: 500+ users → same seat, × K hot seats. Expect exactly 1×201 per seat.
3. General stampede: ~20k requests across seats with skew to "good" seats.
4. Idempotent retry: same key fired in parallel ×M → 1 reservation. Same key + other seats → 409.
5. Per-user limit: one user, 10 parallel reserves, limit 4 → ≤4 seats.
6. Spoof: body `user_id` of another user → acts as token user only. Cancel of other's reservation → 403/404.
7. Final: `GET /shows/{id}`; check `available + held + confirmed == total`; check no seat has two owners; scrape `/prometheus` and compare counters to API state.
8. Exit code ≠ 0 if any invariant fails.

---

## 9. Test strategy

| Layer | What | Tool | Conf |
|---|---|---|---|
| Unit | Request hashing, validation, error mapping, money math (long, no float). | JUnit 5 | H |
| Integration | Each endpoint against real Postgres. Migrations. Auth filter. | Testcontainers + MockMvc / RestClient | H |
| Concurrency | Hot seat: 500 threads → 1 winner. Per-user limit: 10 parallel → ≤4. Same key ×50 parallel → 1 reservation. Multi-seat overlap (A1,A2 vs A2,A1) → no deadlock. Cancel vs reserve race. Expiry vs confirm race (if H2/H3). Invariant check after each. | JUnit + ExecutorService + CountDownLatch + Testcontainers | H |
| Readiness | Stop DB container → readiness 503 → liveness 200. | Testcontainers | M |
| Metrics | Counters equal DB state after concurrency test. | Integration | M |
| Load / E2E | Burst tool against local compose and live URL. | burst.sh | H |

---

## 10. CI/CD

| Stage | Trigger | Steps | Tool |
|---|---|---|---|
| CI | Push / PR | `./mvnw verify` (unit + Testcontainers tests), build Docker image | GitHub Actions (Docker available on ubuntu runners) [inferred, H] |
| CD | Push to `main` | Platform auto-deploy from GitHub (Railway/Render) OR GHCR image + SSH deploy (Oracle VM) | Platform / Actions |
| Post-deploy | After deploy | Poll readiness. Run small burst (smoke profile). Fail job on invariant breach. | Actions + burst tool |

Branching: work on feature branches. Merge to `main` with small, incremental commits. [src: :62 asks for real history]

---

## 11. Observability

### 11.1 Metrics (Prometheus, `/actuator/prometheus`)

| Metric | Type | Labels |
|---|---|---|
| `reservations_confirmed_total` | counter | show_id |
| `reservations_declined_total` | counter | show_id, reason = seat_taken / per_user_limit / idempotent_replay / idempotency_key_reuse / invalid |
| `seats` | gauge | show_id, state = available / held / confirmed (read from DB, cached ~1 s) |
| `reservations_cancelled_total`, `holds_expired_total` | counter | show_id |
| `http_server_requests_seconds` | histogram | uri, status (built-in) |
| `hikaricp_connections_*` | gauge | built-in |

Gauges read from DB so they match the API exactly. [inferred, H]
Caveat: counters reset on restart. WRITEUP must say this. [inferred, H]
High-cardinality risk: `show_id` label. OK for few shows. [inferred, M]

### 11.2 Logs

- JSON to stdout. Fields: timestamp, level, request_id, user_id, show_id, outcome, reason, latency_ms. [inferred, H]
- `X-Request-Id` accepted or generated. Returned in response. Put in MDC. [inferred, H]
- Public access options: Grafana Cloud free (Loki) via Logback appender, OR self-hosted Grafana+Loki on Oracle VM, OR screen recording. [src: :57 allows recording]

### 11.3 Dashboards / alerting

- Grafana dashboard JSON committed in repo. Local compose provisions it.
- Hosted: Grafana Cloud free scrapes the public `/prometheus` endpoint (P1–P3), or self-host (P4). [inferred, M]
- Page at 2 am for: readiness failing; 5xx rate > 0; invariant breach (`sum(seats) != total`); double-owner check failure; p99 latency high; pool saturation. Put in WRITEUP.

---

## 12. Deliverable → delivery map

| Deliverable | How delivered | Where |
|---|---|---|
| Public repo + history | GitHub public repo. Small commits per phase. | GitHub (Q5) |
| Live URL | Platform from §7. | README top |
| Burst script | `burst.sh <BASE_URL>` + `make burst`. | `burst/`, README |
| Metrics access | Public `/actuator/prometheus` + Grafana dashboard link. | README |
| Logs access | Grafana Cloud/Loki link or recording. | README |
| WRITEUP.md | All sections listed in :66. Includes honest AI usage log. | repo root |
| Docker | `Dockerfile` + `docker-compose.yml` (app, postgres, prometheus, grafana). | repo root |

---

## 13. Proposed delivery phases (for next discussion)

| Phase | Output |
|---|---|
| 0 | Skeleton: Maven, Boot app, Dockerfile, compose, Flyway, health, CI. |
| 1 | Shows API + data model. |
| 2 | Auth (JWT + token endpoint). |
| 3 | Reserve: atomic decision, per-user limit, idempotency. Concurrency tests. |
| 4 | Cancel / hold expiry. Tests. |
| 5 | Metrics, structured logs, request id. Dashboard. |
| 6 | Burst tool. Run local. |
| 7 | Deploy. Cold-start check. Live burst. Tune. |
| 8 | README, WRITEUP.md. |

---

## 14. Open questions (need your decision)

| ID | Question | My recommendation |
|---|---|---|
| Q1 | Hold model: H1 (confirm at once + cancel), H2 (hold + confirm + TTL), or H3 (both)? | H3 if time allows; else H1. |
| Q2 | Idempotency: store declined (409) results as final, or only successes? | Only successes. |
| Q3 | Token minting: open `POST /auth/token` for users + admin secret for admin role. OK? | Yes. Graders need it. |
| Q4 | Budget: OK to pay ~$5–7/month (Railway) or must be $0 (Oracle VM)? | Railway if paid is OK. |
| Q5 | GitHub account / repo name for the public repo? Push only after your approval. | — |
| Q6 | Per-user limit: global default 4, or settable per show in `POST /shows`? | Settable, default 4. |

## 15. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Edge 5xx under 20k burst on small instance | Fails C-2 | Bigger instance (P3/P4), bounded queue, fast decline path, tune pool. |
| Free DB expiry / sleep | Live URL down at grading | Avoid Render free PG. Document wake behaviour. |
| Hold-model ambiguity | Grader expects other semantics | Document clearly. Support both (H3). |
| JVM start time on small CPU | Cold start slow | CDS, layered jar, lazy init off for hot path only. |
| Counter reset on restart | Metric vs API mismatch | Gauges from DB. Document. |

## 16. Sources

- Problem statement: `problem-statement.txt`
- [Hello Interview — Ticketmaster breakdown](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster)
- [Render free tier docs](https://render.com/docs/free)
- [Render vs Railway vs Fly.io pricing 2026 (dev.to)](https://dev.to/pavel-hostim/render-vs-railway-vs-flyio-pricing-compared-2026-2e5p)
- [Railway vs Render vs Fly.io benchmarks (techsy.io)](https://techsy.io/en/blog/railway-vs-render-vs-fly-io)
- [Render vs Railway vs Fly.io (tech-insider.org)](https://tech-insider.org/render-vs-railway-vs-fly-io-2026/)
- [Koyeb free tier (agentdeals.dev)](https://agentdeals.dev/vendor/koyeb)
- [Neon free tier FAQ](https://neon.com/faqs/managed-postgres-databases-free-tier)
- [Oracle Always Free A1 (cnx-software)](https://cnx-software.com/?p=90856)
