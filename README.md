# GetMySeat

[![CI](https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml/badge.svg)](https://github.com/BiLaL-159/getMySeat/actions/workflows/ci.yml)

A live-event ticketing platform. Organizers list Events and schedule Shows at Venues; Customers hold and pay for tickets, either numbered Seats or General Admission capacity, without any seat ever being sold twice.

> **Status:** Phase 1 (catalogue and access control) is complete: Organizer Applications, Venue approval, Events, Shows with Section Prices, and public browsing. See the [Phase 1 demo](#phase-1-demo). Holds and Bookings (Phase 2) are next.

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

A Hold lasts `HOLD_TIME` (default `10m`, any Spring duration such as `90s`) before it expires.

## API

Everything lives under `/api/v1` and needs a Keycloak access token (`Authorization: Bearer ...`) unless it's marked Public. Public endpoints still read a token if you send one, so an owner can see their own drafts.

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

### Venues

An Organizer proposes a Venue with its Section layout, and an Admin approves it. Once a Venue is approved its layout is fixed and any Organizer can use it.

| Endpoint | Who | What |
|---|---|---|
| `POST /api/v1/venues`, `PUT /api/v1/venues/{id}` | Organizer | Create a draft (`name`, `address`, `city`, IANA `timeZone`) or change its details. |
| `POST /api/v1/venues/{id}/sections` | Organizer | Add a Section. `SEATED` Sections take `rows`, each a letter label plus either `seatCount` (Seats 1 to n) or `seatNumbers`. `GENERAL_ADMISSION` Sections take a `capacity` instead. |
| `PUT`, `DELETE /api/v1/venues/{id}/sections/{sectionId}` | Organizer | Rename a Section, change its capacity, or remove it. |
| `POST /api/v1/venues/{id}/sections/{sectionId}/seats`, `DELETE .../seats/{seatId}` | Organizer | Add rows of Seats, or remove one Seat. |
| `POST /api/v1/venues/{id}/submit` | Organizer | Send it for review. It needs at least one Section, and Seats in every Seated Section. |
| `GET /api/v1/venues/mine` | Organizer | Your Venues in any status. |
| `GET /api/v1/admin/venues?status=` | Admin | Venues with their full layout, oldest submission first. |
| `POST /api/v1/admin/venues/{id}/approve`, `.../reject` | Admin | Decide. A rejection needs a `reason`, and the Organizer can fix the Venue and resubmit. |
| `GET /api/v1/venues?q=&city=` | Public | Search approved Venues. `q` matches part of the name and `city` the whole city, both ignoring case. |
| `GET /api/v1/venues/{id}` | Public | A Venue's Sections and Seats. Approved Venues only, unless you're the owner or an Admin. |

Status goes `DRAFT → PENDING_REVIEW → APPROVED | REJECTED`, and back to `PENDING_REVIEW` when a rejected Venue is resubmitted. The layout can change only in `DRAFT` or `REJECTED`, so any other change is `409`. Section names are unique within a Venue, and Seat labels (row plus number, such as `A12`) are unique within a Section. Someone else's unapproved Venue is `404` to you, and changing someone else's approved Venue is `403`. Seats keep their ids once the Venue is approved.

### Events

An Organizer creates Events as drafts, edits them and publishes them. Drafts are private to their owner; published Events are public.

| Endpoint | Who | What |
| --- | --- | --- |
| `POST /api/v1/events` | Organizer | Create a draft with `title`, `description`, `category` (`MUSIC`, `COMEDY`, `THEATRE`, `DANCE`, `SPORTS`, `CONFERENCE`, `WORKSHOP`, `FAMILY` or `OTHER`) and `language` (an ISO 639-1 code such as `en`). |
| `PUT /api/v1/events/{id}` | Organizer | Edit your Event, draft or published. Send the same fields plus the `version` you last read; if the Event changed since, you get `409` and should reload it. |
| `POST /api/v1/events/{id}/publish` | Organizer | `DRAFT → PUBLISHED`. `409` if it's already published. |
| `GET /api/v1/events/mine` | Organizer | Your Events, drafts included, newest first and paginated. Sort by `createdAt` or `title`. |
| `GET /api/v1/events?q=&city=&category=&from=&to=` | Anyone | Published Events, most recently published first and paginated. Every filter is optional. `q` matches part of the title or description and `category` the whole category. `city`, `from` and `to` match Events with at least one upcoming published Show in that city (ignoring case) starting between those ISO dates, inclusive. Dates are taken in the Venue's time zone, so a Show at 1 am in Mumbai counts for that Mumbai date. When you combine them, one Show must match all three. Sort by `publishedAt` or `title`. |
| `GET /api/v1/events/{id}` | Anyone | A published Event. Signed in, you also see your own drafts. |

Someone else's draft is `404` to every caller, and changing someone else's published Event is `403`. Only the owner sees `ownerSubject`.

### Shows

A Show is one occurrence of an Event at an approved Venue. The Event's owner schedules and prices it as a draft, then publishes it.

| Endpoint | Who | What |
| --- | --- | --- |
| `POST /api/v1/events/{id}/shows` | Organizer | Schedule a draft Show of your Event with `venueId` (an approved Venue) and a future `startsAt`. |
| `PUT /api/v1/shows/{id}` | Organizer | Move a draft Show to another `venueId` or `startsAt`, with the `version` you last read. Moving it to another Venue drops the prices of Sections the new Venue doesn't have. |
| `PUT /api/v1/shows/{id}/prices` | Organizer | Replace every Section Price at once: `prices`, each a `sectionId`, `amountPaise` (whole paise, so `50000` is ₹500) and `currency` (`INR`). |
| `POST /api/v1/shows/{id}/publish` | Organizer | Needs a published Event, an approved Venue, a future start time and a price for every Section. After this, the Venue, start time and prices are locked. |
| `GET /api/v1/events/{id}/shows` | Anyone | The Event's upcoming published Shows, soonest first and paginated. Each has its `startsAt` and its `venue` (`id`, `name`, `address`, `city`, `timeZone`). The Event's owner sees every Show, drafts and past ones included. Sort by `startsAt` or `createdAt`. |
| `GET /api/v1/shows/{id}` | Anyone | A published Show, even after it has started, with its `venue` and every Section in layout order. Each Section has its `kind`, `price`, and either a `capacity` (General Admission) or its `seats` (Seated). Signed in, you also see your own drafts, where a Section may have no `price` yet. |
| `GET /api/v1/shows/{id}/availability` | Anyone | What's left to sell at a published Show. Every Section in layout order with its `kind`: a Seated Section lists every Seat `id` with `available`, and a General Admission Section has its `capacity` and how many places are `available`. A draft or unknown Show is `404`. |

A draft Show, or a Show of a draft Event, is `404` to everyone but the Event's owner. Changing someone else's published Show is `403`. Publishing a Show gives it its own inventory in the same transaction: every Seat available and every General Admission place left.

### Holds

A signed-in Customer holds tickets at a published Show while they pay. A Hold is all or nothing: every Seat is claimed with a conditional update (`AVAILABLE → HELD`, in Seat id order) and every General Admission quantity with a decrement that only succeeds while enough places are left, all in one PostgreSQL transaction ([ADR 0003](docs/adr/0003-postgres-owns-inventory.md)).

| Endpoint | Who | What it does |
|---|---|---|
| `POST /api/v1/shows/{id}/holds` | Customer | Holds `{ "seats": [seatId…], "generalAdmission": [{ "sectionId", "quantity" }…] }`, 1 to 10 tickets in all, and returns `201` with the Hold. Each Seat and Section must be part of the Show's Venue, appear once, and be asked for through the right kind of item (`400` otherwise). If anything is gone, nothing is held and the `409` has the type `urn:getmyseat:problem:inventory-unavailable` with `unavailableSeats` (Seat ids) and `unavailableSections` (`{ sectionId, available }`). A deadlock or serialization failure gets the same `409`, so just retry. A draft or unknown Show is `404`; a Show that has started is `409`. |
| `GET /api/v1/holds/{id}` | The Hold's Customer | The Hold: `id`, `showId`, `status` (`ACTIVE` or `RELEASED`), `expiresAt`, `items` in layout order, `totalPaise`, `currency` and `createdAt`. Each item has its `kind` (`SEAT` or `GENERAL_ADMISSION`), `sectionId`, `quantity` (1 for a Seat) and `pricePaise`, the Section Price when the Hold was made; a Seat item also has its `seatId`, `rowLabel` and `seatNumber`. Anyone else's Hold is `404`. |
| `POST /api/v1/holds/{id}/release` | The Hold's Customer | Releases the Hold early and returns it, now `RELEASED`; its Seats and General Admission places are available again. Anyone else's Hold is `404`; a Hold that isn't active is `409`. |
| `GET /api/v1/shows/{id}/holds/mine` | Customer | Your active Hold for the Show, for example after a page reload, or `404` if you have none. |

A Customer has at most one active Hold per Show, enforced by a partial unique index. A new Hold for the same Show releases the previous one in the same transaction, so it can include the same Seats; if the new Hold fails (`400` or `409`), the previous one stays active with its inventory. Two Holds by the same Customer for the same Show at the same moment get one winner, and the other a `409` (`conflict`). Hold transitions are conditional updates (`WHERE status = 'ACTIVE'`), and inventory is given back only by the update that made the transition, so nothing is given back twice.

Conventions every endpoint follows:

- **Errors** are RFC 9457 `ProblemDetail` bodies (`application/problem+json`) with a stable `type`: `urn:getmyseat:problem:validation` (`400`, with an `errors` array of `{field, message}`), `malformed-request` (`400`, a body that isn't valid JSON), `unauthorized` (`401`), `forbidden` (`403`), `not-found` (`404`), `method-not-allowed` (`405`), `conflict` (`409`), `inventory-unavailable` (`409`, see [Holds](#holds)), `upstream-unavailable` (`503`) and `internal-error` (`500`, never with internal details).
- **Pagination:** `page` is 0-based, `size` defaults to 20 and is capped at 100, and `sort` is checked against a per-endpoint allow-list. Pages come back as `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`.
- Authentication is checked before routing, so an anonymous call to a route that doesn't exist gets `401`, not `404`.
- The API is stateless (no sessions or cookies), so CSRF protection is off.

### Frontend

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## Phase 1 demo

This takes one Customer from signing up as an Organizer to a published, priced Show that anyone can browse. Start the stack as in the [Quickstart](#quickstart). You'll need `curl` and `jq`.

**1. Fetch a Customer token** with the dev CLI client:

```bash
token() {
  curl -s -d grant_type=password -d client_id=getmyseat-dev-cli -d username="$1" -d password=password \
    localhost:8180/realms/getmyseat/protocol/openid-connect/token | jq -r .access_token
}
api() {  # api METHOD PATH TOKEN [JSON]
  local args=(-s -X "$1" "localhost:8080/api/v1$2" -H "Authorization: Bearer $3")
  if [ -n "$4" ]; then args+=(-H 'Content-Type: application/json' -d "$4"); fi
  curl "${args[@]}"
}
CUSTOMER=$(token customer)
api GET /me "$CUSTOMER" | jq .roles   # ["CUSTOMER"]
```

**2. Apply to become an Organizer:**

```bash
APPLICATION=$(api POST /organizer-applications "$CUSTOMER" \
  '{"organisationName":"Sunburn Live","contactPhone":"+91 98200 00000","description":"Concerts in Mumbai."}' | jq -r .id)
```

**3. Approve the application as the seed Admin:**

```bash
ADMIN=$(token platform-admin)
api GET '/admin/organizer-applications?status=PENDING' "$ADMIN" | jq '.content[].organisationName'
api POST "/admin/organizer-applications/$APPLICATION/approve" "$ADMIN" | jq .status   # "APPROVED"
```

**4. Refresh the token and see `ORGANIZER`.** Roles come from the token, so the old one doesn't have it yet:

```bash
ORGANIZER=$(token customer)
api GET /me "$ORGANIZER" | jq .roles   # ["CUSTOMER","ORGANIZER"]
```

**5. Propose a Venue and get it approved.** It has a Seated Section with two rows and a General Admission Section:

```bash
VENUE=$(api POST /venues "$ORGANIZER" \
  '{"name":"NSCI Dome","address":"Lala Lajpatrai Marg, Worli","city":"Mumbai","timeZone":"Asia/Kolkata"}' | jq -r .id)
STALLS=$(api POST "/venues/$VENUE/sections" "$ORGANIZER" \
  '{"name":"Stalls","kind":"SEATED","rows":[{"label":"A","seatCount":10},{"label":"B","seatCount":10}]}' | jq -r .id)
PIT=$(api POST "/venues/$VENUE/sections" "$ORGANIZER" \
  '{"name":"Fan Pit","kind":"GENERAL_ADMISSION","capacity":500}' | jq -r .id)
api POST "/venues/$VENUE/submit" "$ORGANIZER" | jq .status        # "PENDING_REVIEW"
api POST "/admin/venues/$VENUE/approve" "$ADMIN" | jq .status     # "APPROVED"
```

**6. Create and publish an Event and a priced Show:**

```bash
EVENT=$(api POST /events "$ORGANIZER" \
  '{"title":"Coldplay: Music of the Spheres","description":"The world tour comes to Mumbai.","category":"MUSIC","language":"en"}' | jq -r .id)
api POST "/events/$EVENT/publish" "$ORGANIZER" | jq .status       # "PUBLISHED"
STARTS_AT=$(date -u -v+30d +%Y-%m-%dT14:30:00Z 2>/dev/null || date -u -d +30days +%Y-%m-%dT14:30:00Z)
SHOW=$(api POST "/events/$EVENT/shows" "$ORGANIZER" "{\"venueId\":\"$VENUE\",\"startsAt\":\"$STARTS_AT\"}" | jq -r .id)
api PUT "/shows/$SHOW/prices" "$ORGANIZER" "{\"prices\":[
  {\"sectionId\":\"$STALLS\",\"amountPaise\":450000,\"currency\":\"INR\"},
  {\"sectionId\":\"$PIT\",\"amountPaise\":250000,\"currency\":\"INR\"}]}" | jq '.prices | length'   # 2
api POST "/shows/$SHOW/publish" "$ORGANIZER" | jq .status         # "PUBLISHED"
echo "Event $EVENT, Show $SHOW"
```

**7. Browse it anonymously in Swagger UI.** Open http://localhost:8080/swagger-ui.html without authorizing, and try:

- `GET /api/v1/events` with `city` = `mumbai` and `category` = `MUSIC`. The Event appears because it has an upcoming published Show in Mumbai.
- `GET /api/v1/events/{eventId}/shows` with the Event's id. The Show is listed with its Venue's name, city and time zone.
- `GET /api/v1/shows/{id}` with the Show's id. Stalls lists its 20 Seats at ₹4,500 (`450000` paise), and Fan Pit has a capacity of 500 at ₹2,500.

The same calls work with curl and no token, for example `curl -s 'localhost:8080/api/v1/events?city=mumbai' | jq`.

Afterwards the seed `customer` is an Organizer, so applying again returns `409`. To run the demo from scratch, reset the stack with `docker compose down -v` and start it again.

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
