# Family Medicine Stock Tracker

Shared household medicine inventory with low-stock and expiry alerts.
Design: [docs/DESIGN.md](docs/DESIGN.md) · Progress: [PROJECT_STATE.md](PROJECT_STATE.md)

## Run locally

Requires Java 21 and Docker Desktop.

```bash
docker compose up -d
```

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

On Windows PowerShell: `$env:SPRING_PROFILES_ACTIVE="local"; .\mvnw.cmd spring-boot:run`

Then open http://localhost:8080/actuator/health and http://localhost:8080/actuator/info
(`boots.total` increases on every restart, which shows the data is stored persistently).

## Test

```bash
./mvnw verify
```

Database integration tests use Testcontainers and are **skipped when Docker isn't running**; CI always runs them.

## Configuration

All settings come from environment variables. See `.env.example`. Production needs
`SPRING_PROFILES_ACTIVE=prod`, `DB_URL`, `DB_USER`, `DB_PASSWORD`. Never commit real values.
