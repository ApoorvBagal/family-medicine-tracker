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

## Frontend (React + Vite PWA in `frontend/`)

The Maven build downloads its own Node (`frontend/node`, pinned in `pom.xml`), runs `npm ci` and
`npm run build` (type-check + Vite), and puts the result in `target/classes/static`, so it ships inside
the jar and is served by Spring Boot at `/`. No system Node needed.

Live-reload while working on the UI (backend running on :8080):

```powershell
cd frontend; $env:Path = "$PWD\node;$env:Path"; .\node\npm.cmd run dev
```

Local dev login: `.env.dev-owner.local` (git-ignored) holds a throwaway owner account on the Neon `dev`
branch, plus the dev-only setup token used to create it.

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
Production (Render) sets `SPRING_PROFILES_ACTIVE=prod`, `DATABASE_URL_UNPOOLED` and, until the owner
account exists, `SETUP_TOKEN` (one-time secret for `POST /api/v1/auth/setup`; setup is refused without it,
and permanently once done).

Alerts (M5): `EMAIL_PROVIDER` (`log` = only log, default; `brevo`), `BREVO_API_KEY`, `EMAIL_FROM` (Gmail address
verified in Brevo), `EMAIL_FROM_NAME`, `APP_BASE_URL` (used in email links), `JOB_TOKEN` (bearer token for the daily
job; the GitHub secret `JOB_TOKEN` must match).

## API (so far)

| Method | Path | Auth |
|---|---|---|
| GET | `/api/v1/auth/setup-status` | public |
| GET | `/api/v1/auth/csrf` | public: sets the `XSRF-TOKEN` cookie; send it back as `X-XSRF-TOKEN` on writes |
| POST | `/api/v1/auth/setup` | public + `SETUP_TOKEN`, once |
| POST | `/api/v1/auth/login` / `logout` | public / logged in |
| GET | `/api/v1/auth/me` | logged in |
| GET/POST | `/api/v1/medicines` (`?q=`, `?archived=`) | logged in |
| GET/PUT | `/api/v1/medicines/{id}` (PUT needs `If-Match: "v<version>"`) | logged in |
| POST | `/api/v1/medicines/{id}/archive`, `/restore` | logged in |
| GET | `/api/v1/medicines/{id}/history` | logged in |
| GET | `/v3/api-docs` (OpenAPI JSON) | logged in |
| GET | `/api/v1/medicines/{id}/stock`, `/movements` | logged in |
| POST | `/api/v1/medicines/{id}/purchases`, `/consumptions`, `/recounts`, `/disposals` (need `Idempotency-Key`) | logged in |
| POST | `/api/v1/stock-movements/{id}/reversal` (undo; needs `Idempotency-Key`) | logged in |
| GET, POST | `/api/v1/alert-recipients`; DELETE `/api/v1/alert-recipients/{id}` | logged in |
| POST | `/api/v1/alerts/test-email`; GET `/api/v1/alerts/status` | logged in |
| POST | `/api/v1/unsubscribe?token=` (link in alert emails) | public, token |
| POST | `/internal/jobs/daily` (GitHub Actions cron, `Daily check` workflow) | `Bearer JOB_TOKEN` |

Errors are `application/problem+json` with a stable `code` (e.g. `INVALID_CREDENTIALS`, `TOO_MANY_ATTEMPTS`).

## Deploy

Merge to `main` → CI → **Deploy** workflow triggers Render, waits until `/actuator/info` reports the tested
commit (`app.commit`), then smoke-tests. Render auto-deploy is off.
