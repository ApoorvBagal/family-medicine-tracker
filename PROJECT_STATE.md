# PROJECT_STATE

_Last updated: 2026-11-14_

## Current phase
**M0 — Walking skeleton, deployed.** Code on GitHub (public repo `ApoorvBagal/family-medicine-tracker`, branch `m0/walking-skeleton`, CI green). Not yet merged to `main`. Deployment part not started.

## Decisions
| ID | Decision | Status |
|---|---|---|
| D0 | Backend: Java Spring Boot (accepted trade-off: slower free-tier cold starts) | Approved 2026-11-14 |
| D1 | Stock tracked in batches (lots), expiry per batch | Approved 2026-11-14 |
| D2 | Frontend: React+Vite on Cloudflare Pages + custom domain (~$10/yr), buy before M1 | Approved 2026-11-14 |
| D3 | Render (backend) + Neon (Postgres) + HTTP-API email (Brevo/Resend) | Approved 2026-11-14 |
| D4 | Server-side sessions in Postgres, closed sign-up, invitations only | Approved 2026-11-14 |
| D5 | "I'll buy it" restock claim in MVP | Approved 2026-11-14 |
| D6 | Java 21 LTS | Approved 2026-11-14 |
| D7 | Spring Boot 4.1.1, Maven wrapper (Maven 3.9.16), Testcontainers 2.0.5, Postgres 17 | Chosen in M0 (matches locally cached versions) |

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

## Unresolved issues
- Docker Desktop not installed locally → can't run Postgres, integration tests or image build on this machine.
- Mockito "self-attaching" JDK warning in tests (harmless now; configure Mockito as a Java agent in M1).
- Make CI fail if integration tests are skipped (e.g. only allow `disabledWithoutDocker` when not `CI=true`).
- Repo is **public**: fine for code (no secrets/data in it), but owner may switch it to private.
- Free-tier terms (Render sleep/SMTP, Neon limits) to verify while setting up accounts.
- Household timezone assumed `Asia/Kolkata` — confirm.

## Next steps (rest of M0)
1. ~~Commit, push, CI green~~ ✅ — next: open PR `m0/walking-skeleton` → `main` and merge.
2. Create Neon project (Postgres 17, region near you) → get connection details.
3. Create Render web service from the repo (Docker, free plan): env vars `SPRING_PROFILES_ACTIVE=prod`, `DB_URL`, `DB_USER`, `DB_PASSWORD`; health check path `/actuator/health`; auto-deploy off.
4. Verify `https://<service>.onrender.com/actuator/health` = UP; note cold-start time.
5. Persistence test: note `boots.total`, restart the service, then redeploy → total must increase each time.
6. Add `deploy.yml` (Render deploy hook after CI passes).
7. Install Docker Desktop (needed from M1 for local DB work).
