# ParkIt

Parking reservation and management system for one privately managed
facility. UCS553 Enterprise Web Application mini project.

Design documents (Version 2.1, source of truth) are in [docs/](docs/):
`SRS.md`, `BUSINESS_RULES.md`, `DATABASE_DESIGN.md`, `ARCHITECTURE.md`.

## Current status: Phase 1 — backend foundation

Implemented:

- Java 21 / Spring Boot 3.5 / Maven project in [backend/](backend/)
- PostgreSQL connectivity (credentials from environment variables only)
- Spring Data JPA / Hibernate with `ddl-auto=validate` (Hibernate never changes the schema)
- Flyway migrations: `V1__extensions.sql`, `V2__core_schema.sql` (the full schema from DATABASE_DESIGN.md §9)
- Package structure from ARCHITECTURE.md §4
- `ParkItException` hierarchy and `GlobalExceptionHandler` (RFC 7807 ProblemDetail, Bean Validation)
- Health check: `GET /api/health`
- JUnit 5, Spring Boot Test and Testcontainers tests

Not implemented yet (later phases): entities, security/login, vehicles,
slots, booking, payment, allocation, frontend. `V3__seed_reference_data.sql`
waits for the facility hours and rates from the project owner.

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
