# Kit Desk

**Company:** Kit Desk · **Founder:** Sammy Mohellebi · SOEN 387, Fall 2026

Kit Desk is a back-end booking service for a campus camera and sound gear desk. The student web portal, the staff check-out tablet and partner systems (for example a course site reserving kit for an assignment) all call the same API to reserve **specific units** (e.g. `CAM-R5-03`) for a date range, without ever double-booking one.

## Features

Everything below works except the emails, which come after the milestone. The notification endpoints in the contract are not built yet for the same reason.

- Browse categories, models and individual units
- Book a specific unit for up to 10 days
- Per-student limits per category on the same days: 1 camera, 2 lenses, 1 light, 2 sound (a camera for week 1 and another for week 3 is fine)
- Gear that hasn't been returned keeps its unit busy, even past its end date
- Cancel any time before pickup; an unclaimed booking expires after its end date
- Early pickup if the unit is back and nobody is booked in between (the booking still covers at most 10 days)
- Late-return failsafe: a booking whose unit is overdue moves to an identical free unit
- Overdue gear blocks new bookings and pickups
- Staff confirm pickups and returns
- Login with JWT; students only ever see their own reservations
- Idempotency keys on booking, cancel, pickup and return, so a retried request never applies twice
- No double-booking under concurrent requests (row locks in the service + a database rule)
- Confirmation and reminder emails via Resend, sent from an outbox by a background worker

## Tech stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · JPA/Hibernate · JWT (HS256, jjwt) + bcrypt · springdoc (Swagger UI) · Docker Compose · JUnit 5 + Testcontainers

## The contract

The API contract is [`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml) (OpenAPI 3.1). It is the source of truth: the code is built to match it, and Swagger UI displays this file rather than a generated one.

## Project layout

```
src/main/java/com/kitdesk/
  api/          Interface layer: controllers, request parsing, error handling. No rules.
  config/       Cross-cutting setup: correlation IDs, the token check, login tokens, clock, scheduling
  service/      Use cases, rules across many records, access rules, transactions and lock order, idempotency, scheduled jobs
  domain/       Entities and the rules about one record's own data (10-day window, availability, status changes)
  repository/   Database access: queries, row locks, version checks
src/main/resources/
  application.yml          Settings, all overridable by environment variables
  static/openapi.yaml      The API contract
  db/migration/            Flyway migrations, run in order on startup
```

## Running it

You need Docker. Nothing else has to be installed.

```bash
cp .env.example .env        # then set the password and JWT_SECRET in .env
docker compose up --build
```

- API: http://localhost:8080
- Swagger UI (shows the contract): http://localhost:8080/swagger-ui.html
- Contract file: http://localhost:8080/openapi.yaml
- Health: http://localhost:8080/health/live and http://localhost:8080/health/ready

The database is stored in a Docker volume, so data survives restarts. To start from a clean database: `docker compose down -v`.

### Running without Docker for the app

Start only the database with `docker compose up -d db`, then run `JWT_SECRET=<32+ characters> ./mvnw spring-boot:run`.

### Tests

```bash
./mvnw test
```

Tests start their own throwaway PostgreSQL 16 container, so Docker must be running. They include HTTP tests of every endpoint and the race tests: 50 students booking one unit at once (exactly one wins), and one student sending 5 lens bookings at once (gets 2). Both run once with the app's locks and once with the locks swapped out inside the test, to show what the database still guarantees on its own.

## Configuration

| Variable | Meaning | Default (local only) |
|---|---|---|
| `DB_URL` | JDBC URL of the database | `jdbc:postgresql://localhost:5432/kitdesk` |
| `DB_USER` | Database user | `kitdesk` |
| `DB_PASSWORD` | Database password | `kitdesk` |
| `JWT_SECRET` | Signs login tokens. At least 32 characters; the app won't start without it | none (required) |
| `KITDESK_TIMEZONE` | Time zone that decides what "today" is for booking rules | `America/Toronto` |
| `PORT` | HTTP port | `8080` |

Real values go in `.env`, which is git-ignored. Never commit secrets.

## Demo accounts

Loaded only with the **`demo` profile**, from `src/main/resources/db/demo/`. Docker Compose and the tests turn that profile on; a real deployment leaves it off, so these accounts never exist there. Every password is `password`.

> **Upgrading an existing local database:** the demo accounts moved out of migration `V2` (Nov 9). A database created before that fails Flyway's check on V2. Reset it once with `docker compose down -v`, then `docker compose up --build`.

| Email | Role | Notes |
|---|---|---|
| `staff@kitdesk.test` | Staff | Confirms pickups and returns |
| `alice@kitdesk.test`, `bob@kitdesk.test` | Student | Active subscription |
| `carol@kitdesk.test` | Student | Lapsed subscription (for the 403 demo) |
| `student01` … `student50@kitdesk.test` | Student | For the concurrency race script |

## Trying it in Swagger UI

1. `POST /auth/login` with `{"email": "alice@kitdesk.test", "password": "password"}` and copy the `token`.
2. Click **Authorize** and paste the token.
3. Book with `POST /reservations`: send any UUID as `Idempotency-Key`, and a body like `{"unitId": "<an id from GET /models/{modelId}/units>", "startDate": "<today>", "endDate": "<today + 2>"}`.
4. Cancel, pickup and return also need `If-Match-Version`: the reservation's current `version`.
