# Write-up — Seat Reservation at Scale

Live: https://seat-reservation-vh11.onrender.com (Render free tier, Render Postgres 17).
Full decision log and measurements: [`docs/DISCOVERY.md`](docs/DISCOVERY.md).

## 1. The atomic decision

The service grants seats in one PostgreSQL transaction (READ COMMITTED), and nowhere else ([`ReserveTransaction`](src/main/java/com/paytm/seats/reservation/ReserveTransaction.java)):

1. Claim the idempotency key: `INSERT … ON CONFLICT (user_id, key) DO NOTHING`.
2. Add to the user's quota row: an upsert guarded by `seats_owned + n <= limit`.
3. Lock the seat rows: `SELECT … WHERE label = ANY(?) ORDER BY label FOR UPDATE`. If any seat is not free → 409 `seat_taken`.
4. Insert the reservation, confirm the seats, and store the response on the key.

**Why it is race-free:**
- One seat is one row (`PRIMARY KEY (show_id, label)`).
- `FOR UPDATE` serialises the writers of that row. After a lock wait, READ COMMITTED re-reads the newest row version, so the second buyer sees `CONFIRMED` and gets 409.
- The check and the write happen under the same lock. There is no read-then-write in the app.
- The per-user limit is one counter row, not a `COUNT(*)`, so parallel requests from one user serialise on it.

**Deadlock:**
- Every transaction locks in one global order: key → quota → seats sorted by label.
- Cancel uses the same order.
- With no cycle, there is no deadlock.

**Partial requests:** all-or-nothing. One taken seat declines the whole request.

**Fast-decline layers:**

| Layer | What it does |
|---|---|
| L2 sold set (in memory) | Declines a seat this instance already saw sold |
| L1 read (non-locking) | Declines a seat the DB shows as taken |
| L3 claim (per-seat lock) | Lets one request per seat reach the DB at a time |

- The layers only decline. They never grant a seat.
- They skip seats the requester owns, so a retry still reaches the transaction and gets its replay.
- CI runs the concurrency tests with the layers on and off.

## 2. Idempotency

- **Where:** table `idempotency_keys`, `PRIMARY KEY (user_id, key)`, with the request hash (SHA-256 of show + sorted seats) and the stored 201 body.
- **Exactly once:** the key insert is step 1 of the same transaction, so the primary key is the guard. A parallel duplicate waits on the uncommitted row and then replays.
- **Same key, same body:** returns the original 201 with `Idempotent-Replayed: true`.
- **Same key, different body:** 409 `idempotency_key_reuse`.
- **Declines are not stored:** a decline rolls back the key too, so the same key can retry later.

## 3. Holds and expiry

- **Model: confirm at once, plus an owner-only cancel** (`POST /reservations/{id}/cancel`). It matches the 201 contract in the problem statement, and clients need no confirm call.
- **Cancel releases only seats that still point at this reservation,** so it can never resurrect a seat sold to someone else.
- **A repeat cancel returns 200.**
- **Holds:** `held` is always 0 in this model. The seat query already treats an expired hold as free, so timed holds can be added later without a cron.

## 4. Consistency vs availability under a partition

**Consistency first.**
- **No seat is granted without the database.** If the DB is unreachable, a purchase fails after the pool timeout.
- **Declines of seats already known as sold still work from memory.** That is safe, because a stale "taken" is a valid race outcome.
- **Health probes:** `/readyz` fails closed (503) when the DB is down. `/livez` checks only the process, so the platform does not restart a healthy app because of a DB outage.
- **With more instances,** every decision still happens in the DB. L2/L3 are per-instance speed-ups.

## 5. Observability — what pages at 2 am

- **Metrics:** public at `/actuator/prometheus`.
  - `reservations_confirmed_total` and `reservations_declined_total{reason}`.
  - Seat gauges per state, read from the DB, so they reconcile with `GET /shows/{id}`.
  - Decline path by layer, DB pool, latency, and the request queue.
- **Logs:** JSON with `request_id`. INFO covers sales and cancels; per-request lines are DEBUG, because they were the largest allocation source under load.
- **Alerts** ([`alerts.yml`](observability/prometheus/alerts.yml)) page on:
  - seat invariant broken (`sum(seats) != capacity`);
  - any 5xx;
  - service down;
  - DB pool saturated;
  - reserve p99 high.
- **Never paged:** 409s. They are correct answers.

**Live full bursts** (20k requests, Render free tier):
- the app returned zero 5xx in every run;
- every run had one winner per hot seat, and the invariant held throughout;
- the metrics reconciled with what clients saw.

The limit is the free instance's CPU, not the database: Postgres runs SQL for under 1% of the time a connection is held. At this size, Render's edge returned about one 5xx per run, and one of the last three runs restarted the instance.

## 6. What I would do next

- **Dashboard and logs:** a hosted Grafana dashboard and log shipping (Loki). Today the dashboard runs in local compose only.
- **Key cleanup:** a scheduled job that deletes idempotency keys older than 24 h. Not built yet.
- **Less memory and CPU per waiting request,** so the free tier absorbs a full burst without restarts. For example: answer requests for sold seats before queueing, with that stage bounded too.
- **Wider burst traffic:** multi-seat and multi-show requests.
- **Timed holds.**
- **More instances,** with shared sold-set invalidation.

## 7. AI usage

I owned the design and used Claude Code as an engineering partner.

**Design:**
- I worked through the design in discovery sessions. I used the AI to lay out options, check Postgres locking semantics, and challenge my choices. I took the decisions; each one is logged in `docs/DISCOVERY.md`.
- My decisions included:
  - the transaction-level lock design and the lock order;
  - all-or-nothing partial requests;
  - success-only idempotency storage;
  - the cancel model;
  - the decline layers;
  - the deploy platform.

**Implementation and testing:**
- The AI implemented each phase from my approved plan.
- It wrote the tests and the burst tool.
- It ran local and live load tests.
- I reviewed every change and merged through PRs.

**Load-test tuning:**
- I drove the investigation: I listed the suspects (client, memory, HTTP pool, DB pool, logic, transactions) and had each one measured locally before changing anything live.
- Used AI to collect the evidence: JFR, GC logs, `pg_stat_statements`.
- I chose the fixes:
  - keep HTTP/2 in the burst client;
  - a bounded request queue;
  - cheaper logging and metrics;
  - stack traces only for real 500s.
- I rejected others after measuring them, such as raising the queue to 128, and I chose not to tweak the test data.
