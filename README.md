# ParkIt

Parking reservation and management system for one privately managed
facility. UCS553 Enterprise Web Application mini project.

Design documents (Version 2.1, source of truth) are in [docs/](docs/):
`SRS.md`, `BUSINESS_RULES.md`, `DATABASE_DESIGN.md`, `ARCHITECTURE.md`.

## Current status: Phase 4 — parking management complete

Implemented:

- **Phase 1 — foundation:** Java 21 / Spring Boot 3.5 / Maven project in [backend/](backend/);
  PostgreSQL (credentials from environment variables only); JPA with `ddl-auto=validate`;
  Flyway migrations `V1`–`V3` (schema from DATABASE_DESIGN.md §9 and approved seed data);
  `ParkItException` hierarchy with RFC 7807 ProblemDetail errors; `GET /api/health`.
- **Phase 2 — domain:** entities, enums and repositories; injected `Clock`; `PolicyProperties`.
- **Phase 3 — accounts:** session login with CSRF, BCrypt, first admin from environment,
  profile and password change, customer vehicles, admin customer search.
- **Phase 4 — parking management:** admin slots (`/api/admin/slots`), slot blocks
  (`/api/admin/slots/{id}/blocks`, `/api/admin/blocks/{id}/cancel`), rates
  (`/api/admin/rates`), facility settings (`/api/admin/facility-settings`), public
  facility info (`GET /api/facility`), and transactional audit logging of these actions.

Not implemented yet (later phases): time and pricing policies, quote, allocation and
holds, payments, cancellation and refunds, QR, check-in/out, reports, audit-log viewer,
frontend.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Set `JAVA_HOME` to a JDK 21 |
| Maven | — | Not needed; use the included wrapper `mvnw` / `mvnw.cmd` |
| PostgreSQL | 16 or newer | Must allow `CREATE EXTENSION btree_gist` |
| Docker | any recent | Only for the integration tests (Testcontainers) |

## 1. Create the database

As a PostgreSQL superuser (e.g. `postgres`), in `psql`:

```sql
CREATE ROLE parkit_app LOGIN PASSWORD 'choose-a-strong-password';
CREATE DATABASE parkit_db OWNER parkit_app;
\c parkit_db
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- needs superuser; V1 then finds it present
```

## 2. Configure environment variables

The application reads its configuration from environment variables
(see [backend/.env.example](backend/.env.example)). No credentials are stored in source control.

| Variable | Required | Default |
|---|---|---|
| `PARKIT_DB_URL` | no | `jdbc:postgresql://localhost:5432/parkit_db` |
| `PARKIT_DB_USERNAME` | **yes** | — |
| `PARKIT_DB_PASSWORD` | **yes** | — |
| `PARKIT_SERVER_PORT` | no | `8080` |
| `PARKIT_DB_POOL_SIZE` | no | `10` |
| `PARKIT_ADMIN_EMAIL` | first start | — |
| `PARKIT_ADMIN_PASSWORD` | first start | — (min 8 characters, max 72 bytes) |
| `PARKIT_ADMIN_PHONE` | first start | — |
| `PARKIT_ADMIN_NAME` | no | `Administrator` |

The first ADMIN account is created at start-up from the `PARKIT_ADMIN_*` variables
only if no ADMIN exists yet; an existing account is never promoted. Afterwards
the variables may be removed.

PowerShell:

```powershell
$env:PARKIT_DB_USERNAME = "parkit_app"
$env:PARKIT_DB_PASSWORD = "your-password"
```

Bash:

```bash
export PARKIT_DB_USERNAME=parkit_app
export PARKIT_DB_PASSWORD=your-password
```

## 3. Build and test

```bash
cd backend
./mvnw verify          # Windows: mvnw.cmd verify
```

- Unit and web-layer tests always run.
- `PostgresSchemaIntegrationTest` starts a PostgreSQL 16 container, applies the
  real Flyway migrations and checks the schema and constraints. It is
  **skipped automatically if Docker is not running**.

## 4. Run the application

```bash
cd backend
./mvnw spring-boot:run   # Windows: mvnw.cmd spring-boot:run
```

On start-up Flyway applies any pending migrations to `parkit_db`, then
Hibernate validates the mappings.

Check health:

```bash
curl http://localhost:8080/api/health
```

```json
{"status":"UP","database":"UP","schemaVersion":"2","timestamp":"2026-11-15T17:10:00Z"}
```

The endpoint returns HTTP 503 with `"status":"DOWN"` if the database is unreachable.

## Error format

All errors are RFC 7807 `application/problem+json` with a stable `errorCode`:

```json
{"type":"about:blank","title":"Bad Request","status":400,
 "detail":"One or more fields are invalid.","errorCode":"VALIDATION_FAILED",
 "fieldErrors":{"email":"must be a well-formed email address"}}
```

## Project layout

```text
backend/
├── pom.xml, mvnw, mvnw.cmd, .mvn/
└── src/
    ├── main/java/com/parkit/
    │   ├── ParkItApplication.java
    │   ├── config/  controller/  controller/admin/  dto/request/  dto/response/
    │   ├── domain/entity/  domain/enums/  domain/policy/
    │   ├── service/  gateway/  repository/  scheduler/  security/
    │   └── exception/
    ├── main/resources/
    │   ├── application.yml
    │   └── db/migration/   V1__extensions.sql, V2__core_schema.sql
    └── test/java/com/parkit/
frontend/   (later phase)
docs/       design documents
```
