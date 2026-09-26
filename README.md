<h1 align="center">GetMySeat</h1>

<p align="center">
  A live-event ticketing platform that never sells the same seat twice.
</p>

<p align="center">
  <a href="https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 4" src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white">
  <img alt="PostgreSQL 17" src="https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white">
  <img alt="Keycloak 26" src="https://img.shields.io/badge/Keycloak-26-4D4D4D?logo=keycloak&logoColor=white">
</p>

<p align="center">
  <a href="#quickstart">Quickstart</a> ·
  <a href="docs/api.md">API reference</a> ·
  <a href="docs/walkthrough.md">Walkthrough</a> ·
  <a href="docs/configuration.md">Configuration</a>
</p>

---

GetMySeat lets **Organizers** list Events and schedule Shows at Venues, and lets **Customers** hold and pay for tickets, either numbered Seats or General Admission places. Every ticket is claimed inside a single PostgreSQL transaction, so two Customers racing for the last seat get exactly one winner.

> [!NOTE]
> GetMySeat is under active development. The catalogue, access control and Holds work today; Bookings and payments are next.

## Table of contents

- [Features](#features)
- [Quickstart](#quickstart)
- [Usage](#usage)
- [Architecture](#architecture)
- [Development](#development)
- [Contributing](#contributing)

## Features

- **Seated and General Admission inventory.** A Venue is made of Sections that are either numbered Seats or a capacity count, and a Show can mix both.
- **No double-selling.** Seats are claimed with conditional updates (`AVAILABLE → HELD`) and General Admission with a decrement that can't go below zero, all or nothing in one transaction. A test races 500 Customers for one Seat and gets exactly one Hold, and 500 for 100 General Admission places and gets exactly 100.
- **Holds.** A Customer holds up to 10 tickets while they pay. A Hold expires on its own after 10 minutes by default, can be released early, and a Customer has at most one active Hold per Show.
- **Organizer onboarding.** A Customer applies to become an Organizer, and approval grants the role in Keycloak.
- **Moderated Venues.** Organizers propose Venues with their layout, Admins approve them, and an approved layout is fixed.
- **Events, Shows and pricing.** Organizers draft and publish Events, schedule Shows and set a price per Section.
- **Public browsing.** Anyone can search Events by text, city, category and date, and see a Show's live availability.
- **A consistent REST API.** Versioned under `/api/v1`, RFC 9457 problem details for every error, validated pagination and sorting, and an OpenAPI spec with Swagger UI.

## Quickstart

You need [Docker](https://docs.docker.com/get-docker/) with Compose.

```bash
git clone https://github.com/BiLaL-159/getMySeat.git
cd getMySeat
docker compose up -d --build --wait
curl localhost:8080/actuator/health   # {"status":"UP",...}
```

This starts:

| Service | URL |
|---|---|
| Backend API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Keycloak | http://localhost:8180 (realm `getmyseat`) |
| PostgreSQL | `localhost:5432`, database `getmyseat` |

To start over with an empty database, run `docker compose down -v`.

## Usage

Fetch a token for one of the seed users and call the API:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=getmyseat-dev-cli \
  -d username=customer -d password=password \
  localhost:8180/realms/getmyseat/protocol/openid-connect/token | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/me
```

Browsing needs no token at all:

```bash
curl -s 'localhost:8080/api/v1/events?city=mumbai&category=MUSIC' | jq
```

- The [walkthrough](docs/walkthrough.md) takes one Customer from applying as an Organizer to a published, priced Show, entirely with `curl`.
- The [API reference](docs/api.md) covers every endpoint, who can call it and how it fails.
- Swagger UI lists the same endpoints interactively; use *Authorize* to paste a token.

### Seed users

The local stack imports a Keycloak realm with these accounts. They are for development only; never reuse them anywhere else.

| Account | Username | Password |
|---|---|---|
| Customer | `customer` | `password` |
| Organizer | `organizer` | `password` |
| Admin | `platform-admin` | `password` |
| Keycloak admin console | `admin` | `admin` |
| PostgreSQL | `getmyseat` | `getmyseat` |

<details>
<summary>Keycloak clients</summary>

The realm defines the `CUSTOMER`, `ORGANIZER` and `ADMIN` roles, and everyone who registers gets `CUSTOMER`. Access tokens must carry the `getmyseat-api` audience.

| Client | Type | Used for |
|---|---|---|
| `getmyseat-frontend` | Public | The web app, with Authorization Code + PKCE |
| `getmyseat-dev-cli` | Public, **dev only** | Fetching tokens with `curl` through the password grant |
| `getmyseat-backend` | Confidential | The backend's service account, which grants `ORGANIZER` when an Admin approves an application. Dev secret: `getmyseat-backend-dev-secret` |

The realm lives in [`infra/keycloak/getmyseat-realm.json`](infra/keycloak/getmyseat-realm.json) and is imported on startup.

</details>

## Architecture

```mermaid
flowchart LR
    client["Web app / curl"] -->|"OIDC login"| keycloak["Keycloak"]
    client -->|"Bearer JWT"| backend
    subgraph backend["Spring Boot backend (modular monolith)"]
        access["access"]
        catalogue["catalogue"]
        booking["booking"]
        payment["payment"]
        notification["notification"]
    end
    backend -->|"validates tokens, grants roles"| keycloak
    backend --> postgres[("PostgreSQL")]
```

The backend is a modular monolith. Each module under `com.getmyseat` owns its tables and talks to the others only through their public APIs, so modules can later be extracted into services.

| Module | Responsibility |
|---|---|
| `access` | Token validation, roles, Organizer Applications |
| `catalogue` | Venues, Sections and Seats, Events, Shows, Section Prices |
| `booking` | Per-Show inventory, availability and Holds |
| `payment` | Payments and refunds (planned) |
| `notification` | Emails to Customers (planned) |

PostgreSQL is the single source of truth for inventory. Holds rely on row-level conditional updates and a partial unique index rather than application locks, and schema changes are versioned with Flyway.

### Tech stack

| Area | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4, Maven |
| Persistence | PostgreSQL 17, Spring Data JPA, Flyway |
| Identity | Keycloak 26 (OAuth 2.0 / OpenID Connect) |
| Testing | JUnit 5, Testcontainers |
| Frontend | React, TypeScript, Vite |
| CI | GitHub Actions |

## Development

Prerequisites: Docker, JDK 21 and Node 24.

### Backend

Run the dependencies in Docker and the backend on your machine:

```bash
docker compose up -d postgres keycloak
cd backend && ./mvnw spring-boot:run
```

Every setting has a default that points at the compose stack. See [configuration](docs/configuration.md) to override them.

### Frontend

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

The frontend is currently a scaffold; the screens arrive alongside the backend phases.

### Tests

```bash
cd backend && ./mvnw verify   # needs Docker running
```

The integration tests boot the full application against PostgreSQL in Testcontainers and call it over HTTP.

- **API tests** (`@ApiIntegrationTest`) use `TestJwts` to mint signed tokens for any subject and roles, so real token validation runs without a live Keycloak. `FakeRoleGrants` stands in for the Keycloak Admin API and can be told to fail.
- **Keycloak tests** (`KeycloakRealmIT`, `KeycloakRoleGrantsIT`) boot Keycloak from the committed realm export and check the seed users, token audiences and role grants against the real thing.
- **`ApplicationHealthIT`** runs the Flyway migrations and checks that the app and its database report `UP`.

CI runs `./mvnw verify` for the backend and `npm run lint` and `npm run build` for the frontend on every push and pull request.

### Project layout

```
.
├── backend/              Spring Boot application
│   └── src/main/java/com/getmyseat/
│       ├── access/       authentication, roles, Organizer Applications
│       ├── catalogue/    Venues, Events, Shows, prices
│       ├── booking/      inventory, availability, Holds
│       ├── payment/      (planned)
│       ├── notification/ (planned)
│       └── shared/api/   error handling, pagination, OpenAPI
├── frontend/             React + Vite app
├── infra/keycloak/       Keycloak realm export
├── docs/                 API reference, walkthrough, configuration
└── docker-compose.yml    local stack
```

## Contributing

Issues and pull requests are welcome. Before opening a pull request:

1. Open or find an issue that describes the change.
2. Branch off `main` (for example `feat/42-short-name`).
3. Make sure `./mvnw verify` in `backend/` and `npm run lint && npm run build` in `frontend/` pass.
4. Keep API changes consistent with the [conventions](docs/api.md#conventions) and update [the API reference](docs/api.md).
