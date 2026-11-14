# PROJECT_STATE

_Last updated: 2026-11-14_

## Current phase
**M0 — Walking skeleton, deployed: essentially complete (2026-11-14).** Live at https://family-medicine-tracker.onrender.com; push to `main` → CI → automatic deploy → smoke test. Remaining small items: record startup + cold-start times. Next: M1.

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
