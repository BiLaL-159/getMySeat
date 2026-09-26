# API reference

Everything lives under `/api/v1` and needs a Keycloak access token (`Authorization: Bearer ...`) unless it's marked Public. Public endpoints still read a token if you send one, so an owner can see their own drafts.

- **Swagger UI:** http://localhost:8080/swagger-ui.html (public; use *Authorize* to paste a token). The OpenAPI spec is at `/v3/api-docs`.
- **Who am I:** `GET /api/v1/me` returns your subject, name, email and GetMySeat roles. Roles come from the token, so a newly granted role appears only after the token is refreshed.

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=getmyseat-dev-cli \
  -d username=customer -d password=password \
  localhost:8180/realms/getmyseat/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/me
```

## Organizer Applications

A Customer applies to become an Organizer, and an Admin decides.

| Endpoint | Who | What |
|---|---|---|
| `POST /api/v1/organizer-applications` | Customer | Apply with `organisationName`, `contactPhone` and `description`. Your name and email come from your token. `409` if you already have a pending application or are already an Organizer (even before your token shows it). |
| `GET /api/v1/organizer-applications/mine` | Customer | Your latest application, including the rejection reason if it was rejected. You can apply again after a rejection. |
| `GET /api/v1/admin/organizer-applications?status=` | Admin | The queue, oldest first and paginated. `status` is `PENDING`, `APPROVED` or `REJECTED`; leave it out for all. |
| `POST /api/v1/admin/organizer-applications/{id}/approve` | Admin | Grants `ORGANIZER` in Keycloak, then marks the application approved. If Keycloak can't be reached the application stays pending and you get `503`; retrying is safe. If the applicant's Keycloak account no longer exists you get `409`; reject it instead. |
| `POST /api/v1/admin/organizer-applications/{id}/reject` | Admin | Rejects with a required `reason`. |

Deciding an application that's already decided returns `409`, including when two Admins act at once. Every decision records the Admin's subject and when it was made. The applicant sees `ORGANIZER` in `GET /api/v1/me` after their token is refreshed.

## Venues

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

## Events

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

## Shows

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

## Holds

A signed-in Customer holds tickets at a published Show while they pay. A Hold is all or nothing: every Seat is claimed with a conditional update (`AVAILABLE → HELD`, in Seat id order) and every General Admission quantity with a decrement that only succeeds while enough places are left, all in one PostgreSQL transaction. PostgreSQL is the only thing that decides who owns inventory.

| Endpoint | Who | What it does |
|---|---|---|
| `POST /api/v1/shows/{id}/holds` | Customer | Holds `{ "seats": [seatId…], "generalAdmission": [{ "sectionId", "quantity" }…] }`, 1 to 10 tickets in all, and returns `201` with the Hold. Each Seat and Section must be part of the Show's Venue, appear once, and be asked for through the right kind of item (`400` otherwise). If anything is gone, nothing is held and the `409` has the type `urn:getmyseat:problem:inventory-unavailable` with `unavailableSeats` (Seat ids) and `unavailableSections` (`{ sectionId, available }`). A deadlock or serialization failure gets the same `409`, so just retry. A draft or unknown Show is `404`; a Show that has started is `409`. |
| `GET /api/v1/holds/{id}` | The Hold's Customer | The Hold: `id`, `showId`, `status` (`ACTIVE`, `RELEASED` or `EXPIRED`), `expiresAt`, `items` in layout order, `totalPaise`, `currency` and `createdAt`. Each item has its `kind` (`SEAT` or `GENERAL_ADMISSION`), `sectionId`, `quantity` (1 for a Seat) and `pricePaise`, the Section Price when the Hold was made; a Seat item also has its `seatId`, `rowLabel` and `seatNumber`. Anyone else's Hold is `404`. |
| `POST /api/v1/holds/{id}/release` | The Hold's Customer | Releases the Hold early and returns it, now `RELEASED`; its Seats and General Admission places are available again. Anyone else's Hold is `404`; a Hold that has expired, or otherwise isn't active, is `409`. |
| `GET /api/v1/shows/{id}/holds/mine` | Customer | Your active Hold for the Show, for example after a page reload, or `404` if you have none. |

A Customer has at most one active Hold per Show, enforced by a partial unique index. A new Hold for the same Show releases the previous one in the same transaction, so it can include the same Seats; if the new Hold fails (`400` or `409`), the previous one stays active with its inventory. Two Holds by the same Customer for the same Show at the same moment get one winner, and the other a `409` (`conflict`). Hold transitions are conditional updates (`WHERE status = 'ACTIVE'`), and inventory is given back only by the update that made the transition, so nothing is given back twice.

A Hold expires `HOLD_TIME` (10 minutes by default) after it is made, so abandoned checkouts give their inventory back. An active Hold past its `expiresAt` expires as soon as it is read through `GET /holds/{id}` or `GET /shows/{id}/holds/mine`, or replaced by a new Hold for the Show: it becomes `EXPIRED`, gives back its inventory, and `holds/mine` returns `404`. (If the new Hold fails, nothing changes, and the old Hold waits for the next read or cleanup run.) Releasing it is `409`. Expiring on a read can collide with other Holds for the same inventory; that read is then a retryable `409` (`conflict`). Holds that nobody reads are expired by a cleanup job every `HOLD_CLEANUP_INTERVAL` (30 seconds by default), which takes them in batches with `FOR UPDATE SKIP LOCKED`. Availability reads don't expire Holds, so a Seat or place whose Hold has expired but nobody has read can stay unavailable for up to one cleanup interval. The job, lazy reads and releases all end a Hold with the same conditional update, so an expired Hold's inventory comes back exactly once.

## Conventions

Every endpoint follows these:

- **Errors** are RFC 9457 `ProblemDetail` bodies (`application/problem+json`) with a stable `type`: `urn:getmyseat:problem:validation` (`400`, with an `errors` array of `{field, message}`), `malformed-request` (`400`, a body that isn't valid JSON), `unauthorized` (`401`), `forbidden` (`403`), `not-found` (`404`), `method-not-allowed` (`405`), `conflict` (`409`), `inventory-unavailable` (`409`, see [Holds](#holds)), `upstream-unavailable` (`503`) and `internal-error` (`500`, never with internal details).
- **Pagination:** `page` is 0-based, `size` defaults to 20 and is capped at 100, and `sort` is checked against a per-endpoint allow-list. Pages come back as `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`.
- Authentication is checked before routing, so an anonymous call to a route that doesn't exist gets `401`, not `404`.
- The API is stateless (no sessions or cookies), so CSRF protection is off.

