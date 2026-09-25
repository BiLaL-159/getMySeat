# GetMySeat

[![CI](https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml/badge.svg)](https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml)

A live-event ticketing platform. Organizers list Events and schedule Shows at Venues; Customers hold and pay for tickets, either numbered Seats or General Admission capacity, without any seat ever being sold twice.

> **Status:** Phase 1 in progress. The API validates Keycloak tokens and has its shared conventions (errors, pagination, OpenAPI); catalogue features are next.

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

Token validation defaults to the compose Keycloak realm:

| Variable | Default | Purpose |
|---|---|---|
| `JWT_ISSUER_URI` | `http://localhost:8180/realms/getmyseat` | The `iss` every access token must carry |
| `JWT_JWK_SET_URI` | `${JWT_ISSUER_URI}/protocol/openid-connect/certs` | Where signing keys are fetched (lazily, so the app boots without Keycloak) |
| `JWT_AUDIENCE` | `getmyseat-api` | The `aud` every access token must carry |

Approving an Organizer Application grants the `ORGANIZER` realm role through the Keycloak Admin API, signed in as the `getmyseat-backend` client:

| Variable | Default | Purpose |
|---|---|---|
| `KEYCLOAK_URL` | `http://localhost:8180` | Keycloak's base URL, as the backend reaches it |
| `KEYCLOAK_REALM` | `getmyseat` | The realm roles are granted in |
| `KEYCLOAK_BACKEND_CLIENT_ID` | `getmyseat-backend` | The backend's confidential client |
| `KEYCLOAK_BACKEND_CLIENT_SECRET` | `getmyseat-backend-dev-secret` | That client's secret. **Dev only**; always set a real one elsewhere |
| `KEYCLOAK_TIMEOUT` | `5s` | Per request; a slower Keycloak fails the approval with `503` |

## API

Everything lives under `/api/v1` and needs a Keycloak access token (`Authorization: Bearer ...`) unless noted.

- **Swagger UI:** http://localhost:8080/swagger-ui.html (public; use *Authorize* to paste a token). The OpenAPI spec is at `/v3/api-docs`.
- **Who am I:** `GET /api/v1/me` returns your subject, name, email and GetMySeat roles. Roles come from the token, so a newly granted role appears only after the token is refreshed.

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=getmyseat-dev-cli \
  -d username=customer -d password=password \
  localhost:8180/realms/getmyseat/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/me
```

### Organizer Applications

A Customer applies to become an Organizer, and an Admin decides.

| Endpoint | Who | What |
|---|---|---|
| `POST /api/v1/organizer-applications` | Customer | Apply with `organisationName`, `contactPhone` and `description`. Your name and email come from your token. `409` if you already have a pending application or are already an Organizer (even before your token shows it). |
| `GET /api/v1/organizer-applications/mine` | Customer | Your latest application, including the rejection reason if it was rejected. You can apply again after a rejection. |
| `GET /api/v1/admin/organizer-applications?status=` | Admin | The queue, oldest first and paginated. `status` is `PENDING`, `APPROVED` or `REJECTED`; leave it out for all. |
| `POST /api/v1/admin/organizer-applications/{id}/approve` | Admin | Grants `ORGANIZER` in Keycloak, then marks the application approved. If Keycloak can't be reached the application stays pending and you get `503`; retrying is safe. If the applicant's Keycloak account no longer exists you get `409`; reject it instead. |
| `POST /api/v1/admin/organizer-applications/{id}/reject` | Admin | Rejects with a required `reason`. |

Deciding an application that's already decided returns `409`, including when two Admins act at once. Every decision records the Admin's subject and when it was made. The applicant sees `ORGANIZER` in `GET /api/v1/me` after their token is refreshed.

Conventions every endpoint follows:

- **Errors** are RFC 9457 `ProblemDetail` bodies (`application/problem+json`) with a stable `type`: `urn:getmyseat:problem:validation` (`400`, with an `errors` array of `{field, message}`), `malformed-request` (`400`, a body that isn't valid JSON), `unauthorized` (`401`), `forbidden` (`403`), `not-found` (`404`), `method-not-allowed` (`405`), `conflict` (`409`), `upstream-unavailable` (`503`) and `internal-error` (`500`, never with internal details).
- **Pagination:** `page` is 0-based, `size` defaults to 20 and is capped at 100, and `sort` is checked against a per-endpoint allow-list. Pages come back as `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`.
- Authentication is checked before routing, so an anonymous call to a route that doesn't exist gets `401`, not `404`.
- The API is stateless (no sessions or cookies), so CSRF protection is off.

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
| Backend's Keycloak client | `getmyseat-backend` | `getmyseat-backend-dev-secret` (client secret) |

The realm defines the `CUSTOMER`, `ORGANIZER` and `ADMIN` realm roles. Everyone who registers gets `CUSTOMER`. Two clients issue access tokens with the `getmyseat-api` audience the backend requires:

- `getmyseat-frontend`: public SPA client using Authorization Code + PKCE.
- `getmyseat-dev-cli`: **dev only**. It allows the password grant so you can fetch a token with curl:

```bash
curl -s -d grant_type=password -d client_id=getmyseat-dev-cli \
  -d username=organizer -d password=password \
  localhost:8180/realms/getmyseat/protocol/openid-connect/token
```

A third client, `getmyseat-backend`, is confidential and has a service account with `realm-management` `manage-users` and `view-realm`. The backend uses it to grant `ORGANIZER` when an Admin approves an Organizer Application.

The realm is defined in [`infra/keycloak/getmyseat-realm.json`](infra/keycloak/getmyseat-realm.json) and imported on startup.

## Tests

```bash
cd backend && ./mvnw verify   # needs Docker running
```

- `ApplicationHealthIT` boots the full app against PostgreSQL in Testcontainers, runs the Flyway migrations and checks that `/actuator/health` (including the database) is `UP`.
- `KeycloakRealmIT` boots Keycloak with the committed realm export and checks that each seed user gets a token carrying the right realm role and the `getmyseat-api` audience.
- `KeycloakRoleGrantsIT` boots Keycloak from the realm export next to the full app. It checks that the backend's client can grant `ORGANIZER` (and that granting again is a no-op), and that a real Keycloak token is accepted and its roles mapped.
- API tests are annotated `@ApiIntegrationTest`: the full app against Testcontainers Postgres, called over HTTP with a `RestTestClient`. `TestJwts` stands in for Keycloak, minting signed tokens per request (any subject, name, email and roles) and serving their signing key, so the real token validation runs without a live Keycloak. `FakeRoleGrants` replaces the Keycloak role-granting adapter; it records grants and can be told to fail.

## Repository layout

```
backend/    Spring Boot application
frontend/   React + Vite app
infra/      Keycloak realm export
docs/       Documentation
```
