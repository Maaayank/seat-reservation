# Deploy runbook — live demo (Render free: web service + Postgres)

Style: ASD-STE100. One action per step.
Target: Render web service (Docker) + Render Postgres, both in Singapore, from `render.yaml`.
Fallback database: Neon (section 7). Fallback host: Oracle Always Free VM (D13).

## 0. Before you start

- You need a Render account connected to GitHub.
- Free plans need no card.
- Render free Postgres **expires 30 days after creation** (14-day grace before deletion). Note the date. Move to Neon (section 7) before it expires if the demo must stay up.
- Do not paste secrets in chat or commit them.

## 1. Merge to main

Render deploys `main`. The Blueprint (`render.yaml`) must be on `main` first.

## 2. Create everything from the Blueprint (Render)

1. Go to https://dashboard.render.com → **New** → **Blueprint**.
2. Select `Maaayank/seat-reservation`, branch `main`.
3. Render reads `render.yaml` and shows two resources:
   - database `seat-reservation-db` (free, Singapore, Postgres 17, no external access)
   - web service `seat-reservation` (free, Singapore, Docker)
4. Click **Apply**. Render creates the database, generates `ADMIN_API_KEY` and `JWT_SECRET`, and wires the database credentials into the service.
5. Wait for the first deploy. The build takes several minutes; the app needs about 1 minute to become ready.
6. Copy the service URL, e.g. `https://seat-reservation-xxxx.onrender.com`.
7. Open the service → **Environment** and copy the generated `ADMIN_API_KEY`.

The app creates its tables on first start (Flyway). You do not run SQL by hand.

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

Ping **`/livez` only**. It does not touch the database. (With Neon, pinging `/readyz` would keep the database awake all month and use more than the free compute hours.)

## 6. Metrics and logs (public access)

Covered in the observability step after the first deploy (Grafana Cloud scrape of `/actuator/prometheus`, public dashboard, log shipping).

## 7. Fallback: Neon database

Use this if the Render database expires or fails.

1. Create a Neon project in **AWS Asia Pacific (Singapore) `ap-southeast-1`**, Postgres 17.
2. In **Connection Details**, turn **Connection pooling OFF** (direct host, no `-pooler`). Reason: the pooled endpoint runs PgBouncer in transaction mode; the app sets session timeouts per connection.
3. In the Render service → **Environment**: delete `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, and set:
   | Key | Value |
   |---|---|
   | `DATABASE_URL` | `jdbc:postgresql://<NEON_HOST>/<DATABASE>?sslmode=require` |
   | `DATABASE_USERNAME` | Neon user |
   | `DATABASE_PASSWORD` | Neon password |
4. Save. Render redeploys; Flyway creates the tables in Neon.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Deploy fails on health check | App not ready in time on the free CPU | Check logs; `JAVA_TOOL_OPTIONS` must keep `-XX:TieredStopAtLevel=1`. |
| `readyz` 503 | Database unreachable | Check the `DATABASE_*` values; database not expired (Render) or suspended (Neon). |
| App does not start: `seats.jwt-secret` | Secret missing or short | Render must have `JWT_SECRET` (generated). |
| First request very slow | Free instance was asleep | Expected. Use the keep-awake monitor. |
