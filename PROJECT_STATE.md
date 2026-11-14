# PROJECT_STATE

_Last updated: 2026-11-14_

## Current phase
**M2 - Medicines (2026-11-14).** M0 and M1 complete and live at https://family-medicine-tracker.onrender.com (push to `main` → CI → automatic deploy → smoke test). M2 live. **M3 (stock) built on branch `m3/stock`.** Small open M0 items: record startup + cold-start times.

## Decisions
| ID | Decision | Status |
|---|---|---|
| D0 | Backend: Java Spring Boot (accepted trade-off: slower free-tier cold starts) | Approved 2026-11-14 |
| D1 | Stock tracked in batches (lots), expiry per batch | Approved 2026-11-14 |
| D2 | ~~React+Vite on Cloudflare Pages + custom domain~~ → **revised 2026-11-14: React+Vite PWA served by Spring Boot on the Render URL; no domain; Android only; no native app** | Revised 2026-11-14 (owner: no domain, Android-only) |
| D3 | Render (backend) + Neon (Postgres) + HTTP-API email (Brevo/Resend) | Approved 2026-11-14 |
| D4 | Server-side sessions in Postgres, closed sign-up, invitations only | Approved 2026-11-14 |
| D5 | "I'll buy it" restock claim in MVP | Approved 2026-11-14 |
| D6 | Java 21 LTS | Approved 2026-11-14 |
| D7 | Spring Boot 4.1.1, Maven wrapper (Maven 3.9.16), Testcontainers 2.0.5 | Chosen in M0 (matches locally cached versions) |
| D8 | Postgres **18** everywhere (Neon project runs 18; tests + docker-compose follow) | 2026-11-14 |
| D10 | No local Docker: local dev on a Neon `dev` branch; real-DB tests in CI (Testcontainers) + optional local run against a Neon `test` branch | 2026-11-14 (owner choice) |
| D11 | Runway alerts: medicines with `daily_dose` alert when estimated days left ≤ `warn_days` (default **5**); others use `min_threshold`. Estimate is display/alerting only, never written to the ledger. Daily job moves to M5 | 2026-11-14 (owner: warn at 5 days) |
| D12 | **One owner login + alert-recipient emails** (no invitations/roles in MVP; household table kept for later). Registration needs `SETUP_TOKEN` and closes after the first user. Supersedes D5 ("I'll buy it" dropped) and the invitation parts of D4 | Approved 2026-11-14 |
| D9 | DB connection: explicit `DB_URL` wins; else Neon's `DATABASE_URL_UNPOOLED` (env var or git-ignored `.env.local`) converted to JDBC with `sslmode=verify-full`. Direct, not pooled (Flyway needs session features) | 2026-11-14 |

## Completed
- [x] Design document: [docs/DESIGN.md](docs/DESIGN.md)
- [x] M0 local: Git repo, `.gitignore` (secrets/build output), `.gitattributes` (LF for `mvnw`)
- [x] Spring Boot skeleton (`com.familymeds`): Actuator `health` + `info` only exposed, Flyway `V1__app_boot_log.sql`
- [x] Persistence probe: every startup writes an `app_boot_log` row; `/actuator/info` → `boots.total` + last 5 boots
- [x] Env-based config: `application.yml` (no credential defaults), `application-local.yml`, `application-prod.yml` (JSON logs), `.env.example`
- [x] `docker-compose.yml` (local Postgres 17), multi-stage `Dockerfile` (non-root, JVM tuned for 512 MB)
- [x] GitHub Actions `ci.yml`: build + tests with real Postgres, Docker image build, gitleaks secret scan

## Test results
- 2026-11-14 local `./mvnw verify`: **BUILD SUCCESS** — 6 tests: 2 passed (`BootInfoContributorTest`), 4 skipped (`ApplicationIntegrationTest`, no Docker locally).
- 2026-11-14 CI run #2 (commit 522f0cd): build & test ✅, Docker image build ✅, secret scan ❌ — `gitleaks-action@v2` exited 1 (tool error, not a leak; leaks exit 2). Likely its commit-range logic failing on the parentless LICENSE commit merged from GitHub.
- 2026-11-14 CI run (commit 01234dd, gitleaks CLI 8.24.0 over full history): **all 3 jobs green** — https://github.com/ApoorvBagal/family-medicine-tracker/actions/runs/43573309911
- Caveat: job logs need a GitHub sign-in, so the integration-test count in CI wasn't read directly. Docker is available on the runners so they should run, not skip; to make this certain, CI should fail if Testcontainers tests are skipped (todo below).

- 2026-11-14 **Neon connection verified from laptop**: project `square-moon-64989328` (Singapore, PG 18, branch `production`). Flyway applied V1 on Neon; TLS verify-full works; `boots.total` 1 → 2 → 3 across separate JVM processes = persistence OK.
- 2026-11-14 local `./mvnw verify`: BUILD SUCCESS (unit tests incl. 5 new `NeonDatabaseUrlTest`; integration tests skipped, no Docker).

- 2026-11-14 **Deployed to Render** (free, Singapore): https://family-medicine-tracker.onrender.com — `/actuator/health` UP over HTTPS, HTTP→HTTPS 301, `/actuator/env` 404 (not exposed). `boots.total` = 5: boots #4 and #5 came from two **different** Render containers and both see the laptop rows → data persists across container replacement.
- 2026-11-14 First automated deploy (PR #4 merge): CI ✅, deploy hook ✅, Render redeployed (`boots.total` 5 → 6), but the workflow's "new version live" check **timed out**: it compared `build.time`, which didn't change because Render's Docker cache reused the jar (the merge only touched `.github/` and `*.md`, both `.dockerignore`d). Fix: app publishes `app.commit` (from `RENDER_GIT_COMMIT`) at `/actuator/info`; workflow waits for the exact CI-tested commit. Verified locally (`app.commit` read back correctly). Boot #7 = this local check (laptop still points at Neon `production` — dev branch needed from M1).
- 2026-11-14 **Automated deploy pipeline verified end-to-end** (PR #5 merge, commit d70d7cd): CI ✅ → Deploy ✅ (trigger → waited for tested commit → smoke test) — https://github.com/ApoorvBagal/family-medicine-tracker/actions/runs/43583953066. Live `app.commit` = d70d7cd, `boots.total` = 8.
- Still to record: startup time from Render logs; confirm both a restart **and** a redeploy increment `boots.total`; cold-start time after 15+ min idle.

### M1a — Neon dev/test branches (2026-11-14, branch `m1a/neon-dev-test-branches`)
- Created Neon branches `dev` (br-floral-bonus-aoyzakg3) and `test` (br-super-block-aoo828gx), both from `production`.
- Laptop relinked to `dev` (`.neon`, `.env.local`); startup log names the branch and warns if it is `production`.
- DB tests: `TestDatabase` picks `TEST_DATABASE_URL` (Neon test) → Docker container (CI) → skip locally; **never skipped in CI** (closes the earlier todo). Removed `spring-boot-testcontainers` and `testcontainers-junit-jupiter`.
- `scripts/test-with-neon.ps1` fetches the `test` branch URL from the Neon CLI and runs `verify`.
- Results: `.\scripts\test-with-neon.ps1` → **11 tests, 0 skipped, BUILD SUCCESS** locally without Docker. Plain `verify` → 7 run, 4 skipped. Production `boots.total` stayed at 9 through the test run and a local app run (local run hit `dev`).

### M1b — Login backend (2026-11-14, branch `m1b/auth-backend`)
- Flyway **V2**: `household`, `app_user` (unique lower-case email), `installation` (single row id=1 = setup done; guards concurrent setups), Spring Session JDBC tables (copied verbatim from spring-session-jdbc 4.1.1).
- Endpoints under `/api/v1/auth`: `GET setup-status`, `GET csrf`, `POST setup` (needs `SETUP_TOKEN`, once only, logs the owner in), `POST login`, `POST logout`, `GET me`.
- Security: Spring Security 7 `csrf().spa()` (XSRF-TOKEN cookie → X-XSRF-TOKEN header), sessions in Postgres for **30 days** (`spring.session.timeout`), session id changed and CSRF token rotated on login, BCrypt via delegating encoder, password hash erased from the session, generic login errors, login limiter (5 failures/IP, 10/email per 15 min → 429 + Retry-After), setup-token guessing limited too, problem+json for every error (incl. 401/403 from the security filters), no form login / Basic-auth popups.
- Tests: `AuthIntegrationTest` (12 ordered steps: setup-status, 401, CSRF missing, wrong token, validation without echoing the password, setup + session + CSRF rotation, session row in Postgres with 30-day timeout and no hash, second setup 409, logout invalidates server-side (stolen cookie useless), generic wrong-password/unknown-email, IP rate limit, login). `LoginAttemptLimiterTest` (5, fake clock).
- Test DB isolation: each run gets its own schema `test_<epoch>_<hex>`, set via `hikari.connection-init-sql` (**Neon's proxy ignores the JDBC `currentSchema` option**, which a new guard test caught), dropped at exit; stale ones dropped after a day. Tests also switch off the `.env.local` hook (`app.neon-env.enabled=false`) so they can never pick up the dev branch.
- Bugs caught by the new tests before shipping: session timeout was 30 **minutes** (`server.servlet.session.timeout` is ignored by Spring Session); tests were running in the test branch's `public` schema instead of their own.
- Results: `.\scripts\test-with-neon.ps1` → **29 tests, 0 failures, 0 skipped**; test schema cleaned up afterwards.
- 2026-11-14 **Deployed** (PR #7, commit 0b8a55c): CI ✅ → Deploy ✅ (live commit matched). Production probes: `setup-status` → `{"setupRequired":true}`; `me` without login → 401 problem+json `UNAUTHENTICATED`; `csrf` sets `XSRF-TOKEN`; setup → 403 `SETUP_DISABLED` (no token yet); login without CSRF → 403 `CSRF_TOKEN_INVALID`.
- Deploy note: V2 runs on production at deploy. Setup stays disabled (`SETUP_DISABLED`) until `SETUP_TOKEN` is set on Render.

### M1c — Installable app shell (2026-11-14, branch `m1c/pwa-shell`)
- `frontend/`: React 19.3.1 + Vite 8.3.5 + TypeScript 7.0.2 (versions pinned, each ≥ 2 weeks old; 24 npm packages total, lock file committed). Built by `frontend-maven-plugin` 2.0.2 with its own Node v22.23.4 into `target/classes/static`; Dockerfile copies `frontend/`.
- Screens: starting / "waking up the server" (retries 502/503/network for up to 2 min), unreachable + retry, first-time setup (token, household, name, email, password ×2), login, home (greeting, placeholder card, logout). Error codes mapped to plain-English messages. Large text/touch targets, light + dark.
- PWA: `manifest.webmanifest` (standalone, start_url `/`, 192/512 + maskable 512 icons generated with Java2D). **No service worker yet, on purpose**: Chrome on Android no longer requires one to install, and a badly cached shell is a classic "stuck on old version" bug. Revisit in M7 (offline shell would also hide cold starts).
- Server: strict CSP (`'self'` only; the build has no inline scripts/styles), Permissions-Policy, hashed `/assets/**` cached 1 year immutable, everything else `no-cache`, `.webmanifest` → `application/manifest+json`.
- Tests: `StaticSiteIntegrationTest` (real server on a random port: `/` serves the app with CSP + no-cache, asset cache headers, manifest content type + installability fields, API errors stay JSON).
- Results: `.\scripts\test-with-neon.ps1` → **33 tests, 0 failures, 0 skipped**, frontend built inside the Maven run. Manual browser test locally against Neon `dev`: wrong setup token → clear message; setup → home; reload → still logged in; logout → login screen; wrong password → message; login → home; no console errors (CSP not blocking anything); phone width + dark mode OK.
- 2026-11-14 **Deployed** (PR #8, commit 61867bc): CI ✅ → Deploy ✅ (live commit matched). Live checks: `/` 200 with the app, CSP, Permissions-Policy, HSTS, X-Frame-Options DENY, `Cache-Control: no-cache`; hashed JS `max-age=31536000, public, immutable`; manifest `application/manifest+json`; icons `image/png`; `setup-status` true; a deliberately wrong token → `SETUP_TOKEN_INVALID`, so **SETUP_TOKEN is configured on Render**.
- 2026-11-14 **Owner confirmed on a real Android phone**: production setup completed (home screen shown) and the app **installed** to the home screen (first attempt didn't show the install option; worked after following the Chrome menu steps). **M1 complete.**
- Previously open, now done: installing on a real Android phone (needs the Render deploy + `SETUP_TOKEN`), and the waking screen against a real Render cold start.

### M2 - Medicines (2026-11-14, branch `m2/medicines`)
- Flyway **V3**: `medicine` (name, brand, strength, dosage form, unit, `min_threshold`, `daily_dose`, `warn_days` default 5, storage location, notes, `archived_at`, `version`) with a **duplicate guard** (unique per household on name + strength + form, ignoring case and spaces, active medicines only), and `audit_event` (who/what/when, changed fields as JSON).
- API `/api/v1/medicines`: list (`?q=` name/brand search, `?archived=`), create (201 + Location + ETag), get (ETag), **PUT with required `If-Match`** (412 `STALE_VERSION` / 428 `IF_MATCH_REQUIRED`), `archive`, `restore`, `history`. Duplicates -> 409 `DUPLICATE_MEDICINE` with `existingId`. All queries household-scoped from the session (other households' ids -> 404). Spring's own errors now also carry a `code`. OpenAPI JSON at `/v3/api-docs` (springdoc 3.2.0, behind login).
- Decisions: **D13** JdbcClient instead of JPA (explicit SQL, precise locking for M3); **D14** PUT (full replace) instead of PATCH; **D15** OpenAPI JSON only, no Swagger UI (strict CSP would block its inline styles).
- UI: medicine list (search, archived toggle, empty state), add/edit form ("taken every day" -> daily dose + warn days; "only sometimes" -> warn-below threshold; unit follows form), duplicate message with "Open the existing one", stale-edit message with "Reload latest", archive/restore, change history with friendly field names.
- Tests: `MedicineIntegrationTest` (12: create/get/ETag, duplicate variants, validation, stale If-Match, no-op update, update-into-duplicate, archive/restore/duplicate-on-restore, search incl. literal `%`, history, **another household can't see/edit/archive/read history**, 401, OpenAPI behind login). Each test class now gets its own schema.
- Bugs caught while building (all by tests): JdbcClient can't mix `.param` with a parameter source; Java text blocks drop trailing spaces (the SQL keyword `returning` ran into the column list); audit `changes` came back as a JSON string (now a JSON object).
- Results: `.\scripts\test-with-neon.ps1` -> **45 tests, 0 failures, 0 skipped**. 2026-11-14 **Deployed** (PR #9, commit a03bb96): CI ✅ → Deploy ✅ (live commit matched); live `/api/v1/medicines` and `/v3/api-docs` → 401 without login; new frontend bundle served; no test data written to production. Manual browser test against Neon `dev`: still logged in after a server restart; empty state; added Metformin 500 mg (2/day); duplicate "metformin 500MG" caught with "Open the existing one"; edited dose 2 -> 3, "Saved."; history shows the change; network log shows only the deliberate 409.

### M3 - Stock (2026-11-14, branch `m3/stock`)
- Flyway **V4**: `stock_lot` (a batch: quantity `CHECK >= 0`, optional expiry date), `stock_movement` (append-only header: PURCHASE / CONSUMPTION / CORRECTION / DISPOSAL / REVERSAL, who, when, usable stock after, `UNIQUE(household_id, idempotency_key)`, `reverses_movement_id UNIQUE`), `stock_movement_line` (per-batch change).
- Rules (in `StockService`): every change first **locks the medicine row** (`SELECT ... FOR UPDATE`), then checks the **idempotency key** (after the lock, so simultaneous retries see each other); doses come from **unexpired batches, earliest expiry first**, splitting across batches; **expired stock is never usable** (and can't be added); doses beyond usable stock -> 422 `INSUFFICIENT_STOCK`; **recount** states the number the user saw -> 409 `STOCK_CONFLICT` if stock changed meanwhile (an unchanged count is still recorded); **dispose** removes a batch; **undo** = REVERSAL movement (refused if already undone, if it's an undo, or if the stock was already used).
- API: `GET /medicines/{id}/stock`, `GET /medicines/{id}/movements`, `POST /medicines/{id}/purchases | consumptions | recounts | disposals`, `POST /stock-movements/{id}/reversal`. Every POST needs `Idempotency-Key` (400 if missing); replay -> 200 + `Idempotent-Replayed: true`; key reused for another action -> 422. Medicine list now includes `usableQuantity` / `expiredQuantity`.
- UI: stock panel on each medicine (big usable number, "about N days at X a day", next expiry, expired warning), **Took a dose / Bought more (with expiry month from the pack) / Recount**, batches with "Throw away" for expired ones, recent activity with **Undo** on the latest entry; list shows "58 tablets left - about 19 days". A fresh request id per action, reused on network retries.
- Tests: `StockIntegrationTest` (14: FEFO split across batches, half tablets, expired never usable + disposal, already-expired purchase refused, retry applied once + key reuse, missing key, recount conflict/up/down/unchanged, recount from zero, undo once + kept in history + undo-of-undo refused, undo of used purchase refused, archived refused, **other household 404 everywhere**, list totals, invalid amounts) - all check **ledger total == batch total**. `StockConcurrencyTest` (3, real threads + start gate: 8x5 simultaneous doses all counted; 8 phones vs 5 tablets -> exactly 5 succeed, never below 0; same request x8 at once -> applied once).
- **Mutation check**: with `FOR UPDATE` removed, 2 of 3 concurrency tests fail (stock driven below zero -> DB CHECK error; duplicate retry -> unique-key error). The plain-doses test still passes because each batch update is an atomic `quantity = quantity + delta`; the lock protects the check-then-act steps. Lock restored.
- Results: `.\scripts\test-with-neon.ps1` -> **62 tests, 0 failures, 0 skipped**. Manual browser test against Neon `dev`: bought 60 (exp 08/2027) -> "60 usable - about 20 days - next expiry 08/2027"; dose 1 -> 59; recount 58 -> 58; dose 100 -> "Not enough usable stock: only 58 left."; list shows stock; only deliberate errors in the console. Undo not clicked in the browser (confirmation dialog), covered by 4 API tests.

## Tooling installed (2026-11-14)
- Neon CLI `neon@9.5.0` (global npm, installed by owner), signed in.
- Project-level Neon MCP server: `.mcp.json` (OAuth, URL only, pinned to project) — loads after restarting Claude.
- Project-level skills: `.claude/skills/neon`, `.claude/skills/neon-postgres` (downloaded from neon.com registry, SHA-256 verified). `neon skills` CLI needs Node ≥ 22.20 — after upgrading Node, `neon skills status` can manage/update them.
- `neon link` wrote `.neon` (IDs only) and `.env.local` (secrets) — both git-ignored.

## Unresolved issues
- No Docker locally (by choice, D10): integration tests run in CI; local DB tests via a Neon `test` branch arrive in M1.
- Product context (2026-11-14): Android-only family use; only the father takes regular medication (4–5 medicines); updates from one device; warn at ≤ 5 days of stock.
- Mockito "self-attaching" JDK warning in tests (harmless now; configure Mockito as a Java agent in M1).
- Repo is **public**: fine for code (no secrets/data in it), but owner may switch it to private.
- Free-tier terms (Render sleep/SMTP, Neon limits) to verify while setting up accounts.
- Household timezone assumed `Asia/Kolkata` — confirm.

## Next steps (rest of M0)
1. ~~Commit, push, CI green~~ ✅ — next: open PR `m0/walking-skeleton` → `main` and merge.
2. ~~Neon project + connection~~ ✅ verified from laptop.
3. ~~Render web service~~ ✅ live at https://family-medicine-tracker.onrender.com
4. ~~Health UP over HTTPS~~ ✅ — cold-start time still to note.
5. Persistence: ✅ across container replacement; confirm restart + redeploy each increment.
6. Add `deploy.yml` (Render deploy hook after CI passes).
7. ~~Install Docker Desktop~~ dropped (D10).
8. ~~Answer D12~~ ✅ → plan M1.
