# Deploy runbook — live demo (Render free + Neon free)

Style: ASD-STE100. One action per step.
Target: Render web service (Docker, Singapore) + Neon Postgres (`ap-southeast-1`).
Decision: D13 in `docs/DISCOVERY.md`. Fallback: Oracle Always Free VM (F1).

## 0. Before you start

- You need a GitHub account with this repo, a Render account, and a Neon account.
- All three accept GitHub sign-in. No card is necessary for the free plans.
- Do not paste secrets in chat or commit them. Put them only in the dashboards named below.

## 1. Create the database (Neon)

1. Go to https://console.neon.tech and create a project.
2. Set **Region** to **AWS Asia Pacific (Singapore) `ap-southeast-1`**. The app runs in Render Singapore; the same region keeps queries fast.
3. Set **Postgres version** to 17.
4. Open **Connection Details**.
5. Turn **Connection pooling OFF** (use the direct host, without `-pooler` in the name). Reason: the pooled endpoint runs PgBouncer in transaction mode; the app sets session timeouts per connection.
6. Copy these values:
   - Host, e.g. `ep-xxxx-123456.ap-southeast-1.aws.neon.tech`
   - Database, e.g. `neondb`
   - User, e.g. `neondb_owner`
   - Password
7. Build the JDBC URL:
   ```
   jdbc:postgresql://<HOST>/<DATABASE>?sslmode=require
   ```

The app creates its tables on first start (Flyway). You do not run SQL by hand.

## 2. Create the service (Render)

1. Go to https://dashboard.render.com → **New** → **Blueprint**.
2. Connect GitHub and select `Maaayank/seat-reservation`, branch `main`.
3. Render reads `render.yaml`. It shows the service `seat-reservation` (free, Singapore).
4. Fill the three values that the Blueprint asks for:
   | Key | Value |
   |---|---|
   | `DATABASE_URL` | the JDBC URL from step 1.7 |
   | `DATABASE_USERNAME` | Neon user |
   | `DATABASE_PASSWORD` | Neon password |
5. Click **Apply**. Render generates `ADMIN_API_KEY` and `JWT_SECRET`.
6. Wait for the first deploy. On the free CPU, the build takes several minutes and the app needs about 1 minute to become ready.
7. Copy the service URL, e.g. `https://seat-reservation-xxxx.onrender.com`.
8. Open **Environment** and copy the generated `ADMIN_API_KEY`.

Deploys after this one are automatic: a push to `main` deploys only after GitHub CI passes (`autoDeployTrigger: checksPass`).

## 3. Check the live service

```sh
URL=https://seat-reservation-xxxx.onrender.com
curl -s $URL/livez          # {"status":"UP"}
curl -s $URL/readyz         # {"status":"UP"}  (checks the database)
curl -s $URL/actuator/info  # {"app":{"commit":"<sha>"}}
./burst.sh $URL <ADMIN_API_KEY> --profile smoke
```

The first request after 15 min idle can take about 1 minute (free instance wakes up).

## 4. Connect CI to the live service

In GitHub → repo → **Settings** → **Secrets and variables** → **Actions**:

| Kind | Name | Value |
|---|---|---|
| Variable | `LIVE_URL` | the Render URL |
| Secret | `ADMIN_API_KEY` | the generated admin key |

After this, every green push to `main` runs `post-deploy`: it waits until `/actuator/info` shows the new commit, then runs the smoke burst against the live URL.

## 5. Keep it awake (optional)

1. Create a free monitor at https://uptimerobot.com.
2. Type HTTP(s), URL `<URL>/livez`, interval 5 min.

Ping **`/livez` only**. `/readyz` queries the database and keeps Neon awake all month, which uses more than the free compute hours.

## 6. Metrics and logs (public access)

Covered in the observability step after the first deploy (Grafana Cloud scrape of `/actuator/prometheus`, public dashboard, log shipping).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Deploy fails on health check | App not ready in time on the free CPU | Check logs; `JAVA_TOOL_OPTIONS` must keep `-XX:TieredStopAtLevel=1`. |
| `readyz` 503 | Database unreachable | Check the three `DATABASE_*` values; Neon project not suspended. |
| App does not start: `seats.jwt-secret` | Secret missing or short | Render must have `JWT_SECRET` (generated). |
| First request very slow | Free instance was asleep | Expected. Use the keep-awake monitor. |
