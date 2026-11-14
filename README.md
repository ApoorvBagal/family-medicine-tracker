# Family Medicine Stock Tracker

Shared household medicine inventory with "days of stock left" and expiry alerts.
Design: [docs/DESIGN.md](docs/DESIGN.md) · Progress: [PROJECT_STATE.md](PROJECT_STATE.md)

Live: https://family-medicine-tracker.onrender.com (free tier: first request after ~15 min idle wakes the server).

## Databases (Neon branches)

| Neon branch | Used by |
|---|---|
| `production` | Render only. Never point your laptop at it. |
| `dev` | Running the app on your laptop (`.env.local`, written by `neon link --branch dev`) |
| `test` | Database tests on your laptop (`scripts/test-with-neon.ps1`) |

No Docker is needed locally. Requires Java 21 and the Neon CLI (`neon login`, folder linked via `.neon`).

## Run locally (uses the `dev` branch)

```powershell
.\mvnw.cmd spring-boot:run
```

The startup log says which branch is used, e.g. `Using Neon database from .env.local (..., Neon branch 'dev')`.
If `.env.local` ever points at production, a warning is logged. Fix it with `neon link --branch dev -y`.

Then open http://localhost:8080/actuator/health and http://localhost:8080/actuator/info.

## Test

All tests, including database tests, against the Neon `test` branch:

```powershell
.\scripts\test-with-neon.ps1
```

Plain `.\mvnw.cmd verify` runs the unit tests and **skips** the database tests. In CI, database tests
always run (against Postgres 18 in Docker on GitHub's runners) and are never skipped.

## Configuration

All settings come from environment variables; nothing secret is committed. Database connection order:
`DB_URL`/`DB_USER`/`DB_PASSWORD` if set, else Neon's `DATABASE_URL_UNPOOLED` (env var, or `.env.local`).
Production (Render) sets `SPRING_PROFILES_ACTIVE=prod` and `DATABASE_URL_UNPOOLED`.

## Deploy

Merge to `main` → CI → **Deploy** workflow triggers Render, waits until `/actuator/info` reports the tested
commit (`app.commit`), then smoke-tests. Render auto-deploy is off.
