# Kit Desk

**Company:** Kit Desk (working name) · **Founder:** Sam (solo) · SOEN 387, Fall 2026

Kit Desk is a back-end booking service for a campus camera and sound gear desk. The student web portal, the staff check-out tablet and partner systems (for example a course site reserving kit for an assignment) all call the same API to reserve **specific units** (e.g. `CAM-R5-03`) for a date range, without ever double-booking one.

## Features

Status as of the scaffolding: the project skeleton, database schema and demo data are in place. The items below are the planned feature list.

- Browse categories, models and individual units
- Book a specific unit for up to 10 days
- Per-student limits per category: 1 camera, 2 lenses, 1 light, 2 sound
- Cancel any time before pickup; an unclaimed booking expires at its end date
- Overdue gear blocks new bookings and pickups
- Staff confirm pickups and returns
- Login with JWT; students only ever see their own reservations
- Idempotency keys on booking, so a retried request never books twice
- No double-booking under concurrent requests (row locks in the service + a database rule)
- Confirmation and reminder emails via Resend, sent from an outbox by a background worker

## Tech stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · JPA/Hibernate · springdoc (Swagger UI) · Docker Compose · JUnit 5 + Testcontainers

## Project layout

```
src/main/java/com/kitdesk/
  api/          Interface layer: controllers, request/response objects, error handling
  service/      All business rules and transactions
  domain/       Entities and enums
  repository/   Database access (Spring Data)
src/main/resources/
  application.yml          Settings, all overridable by environment variables
  db/migration/            Flyway migrations, run in order on startup
```

## Running it

You need Docker. Nothing else has to be installed.

```bash
cp .env.example .env        # then edit the password in .env
docker compose up --build
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health/liveness and `/readiness`

The database is stored in a Docker volume, so data survives restarts. To start from a clean database: `docker compose down -v`.

### Running without Docker for the app

Start only the database with `docker compose up -d db`, then run `./mvnw spring-boot:run`.

### Tests

```bash
./mvnw test
```

Tests start their own throwaway PostgreSQL 16 container, so Docker must be running.

## Configuration

| Variable | Meaning | Default (local only) |
|---|---|---|
| `DB_URL` | JDBC URL of the database | `jdbc:postgresql://localhost:5432/kitdesk` |
| `DB_USER` | Database user | `kitdesk` |
| `DB_PASSWORD` | Database password | `kitdesk` |
| `PORT` | HTTP port | `8080` |

Real values go in `.env`, which is git-ignored. Never commit secrets.

## Demo accounts

Seeded by migration `V2`. Every password is `password`.

| Email | Role | Notes |
|---|---|---|
| `staff@kitdesk.test` | Staff | Confirms pickups and returns |
| `alice@kitdesk.test`, `bob@kitdesk.test` | Student | Active subscription |
| `carol@kitdesk.test` | Student | Lapsed subscription (for the 403 demo) |
| `student01` … `student50@kitdesk.test` | Student | For the concurrency race script |
