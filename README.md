# GetMySeat

[![CI](https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml/badge.svg)](https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml)

A live-event ticketing platform. Organizers list Events and schedule Shows at Venues; Customers hold and pay for tickets, either numbered Seats or General Admission capacity, without any seat ever being sold twice.

> **Status:** Phase 0, foundations. The skeleton, local stack and CI are in place; domain features start in Phase 1.

## Stack

| Area | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4, Maven |
| Persistence | PostgreSQL 17, Spring Data JPA, Flyway |
| Identity | Keycloak 26 (OAuth2 / OIDC) |
| Testing | JUnit 5, Testcontainers |
| Frontend | React + TypeScript + Vite |
| CI | GitHub Actions |

The backend is a modular monolith. `catalogue`, `booking`, `payment` and `notification` are separate modules under `com.getmyseat` that talk through public APIs and never read each other's tables.

## Quickstart

Prerequisites: Docker. For running outside containers you also need JDK 21 and Node 24.

```bash
git clone https://github.com/BiLaL-159/getMySeat.git
cd getMySeat
docker compose up -d --build --wait
curl localhost:8080/actuator/health   # {"status":"UP",...}
```

| Service | URL |
|---|---|
| Backend | http://localhost:8080 |
| Keycloak | http://localhost:8180 (realm `getmyseat`) |
| PostgreSQL | `localhost:5432`, database `getmyseat` |

### Run the backend from your IDE / host

```bash
docker compose up -d postgres keycloak
cd backend && ./mvnw spring-boot:run
```

The datasource defaults to the compose Postgres; override it with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.

### Frontend

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## Local credentials (dev only)

These exist only in the local stack. Never reuse them anywhere else.

| What | Username | Password |
|---|---|---|
| Keycloak admin console | `admin` | `admin` |
| Seed Customer | `customer` | `password` |
| Seed Organizer | `organizer` | `password` |
| Seed Admin | `platform-admin` | `password` |
| PostgreSQL | `getmyseat` | `getmyseat` |

The realm defines the `CUSTOMER`, `ORGANIZER` and `ADMIN` realm roles. Everyone who registers gets `CUSTOMER`. The realm has two clients:

- `getmyseat-frontend`: public SPA client using Authorization Code + PKCE.
- `getmyseat-dev-cli`: **dev only**. It allows the password grant so you can fetch a token with curl:

```bash
curl -s -d grant_type=password -d client_id=getmyseat-dev-cli \
  -d username=organizer -d password=password \
  localhost:8180/realms/getmyseat/protocol/openid-connect/token
```

The realm is defined in [`infra/keycloak/getmyseat-realm.json`](infra/keycloak/getmyseat-realm.json) and imported on startup.

## Tests

```bash
cd backend && ./mvnw verify   # needs Docker running
```

- `ApplicationHealthIT` boots the full app against PostgreSQL in Testcontainers, runs the Flyway migrations and checks that `/actuator/health` (including the database) is `UP`.
- `KeycloakRealmIT` boots Keycloak with the committed realm export and checks that each seed user gets a token carrying the right realm role.

## Repository layout

```
backend/    Spring Boot application
frontend/   React + Vite app
infra/      Keycloak realm export
docs/       Documentation
```
