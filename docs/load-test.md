# Load test: racing for Holds

This k6 run shows that the Hold guarantees survive realistic load on the `docker compose` stack. 300 Customers raced for 50 Seats and 200 General Admission places at one Show for almost two minutes. At the end, no Seat was in two Holds and the General Admission Section wasn't oversold. There were no `5xx` responses.

The script is [`load-test/holds.js`](../load-test/holds.js). The README explains [how to run it](../README.md#load-test).

## Environment

| | |
|---|---|
| Date | 2026-09-26 |
| Commit | `9568b71` plus the load test itself |
| Machine | Apple M2 laptop, 8 cores, 8 GB RAM, macOS 15.6 |
| Docker | Docker Desktop 29.6, with 8 CPUs and 4 GB of memory for the VM |
| Stack | `docker compose`: backend, PostgreSQL 17 and Keycloak 26 with their default settings. That includes Hikari's default pool of 10 database connections |
| Load generator | k6 2.3.0 (`grafana/k6`) as a service on the same compose network, so it shares the VM's CPUs with the stack |

Everything ran on one laptop, so the latency figures are a floor for correctness under contention, not a capacity benchmark.

## Load profile

Setup creates 300 Customer accounts through the Keycloak Admin API and signs each one in. Then, as the seed Organizer and Admin, it publishes a Show through the API. The Show's Venue has a Seated Section of 50 Seats (rows A to E, 10 Seats each) and a General Admission Section with 200 places.

The `race` scenario ramps virtual users with `ramping-vus`. Each virtual user is one Customer.

| Stage | Duration | Virtual users |
|---|---|---|
| Ramp up | 15 s | 0 → 100 |
| Ramp up | 15 s | 100 → 300 |
| Hold | 60 s | 300 |
| Ramp down | 15 s | 300 → 0 |

In every iteration a Customer does the following:

1. Reads the Show's availability.
2. Asks to hold, with a fresh `Idempotency-Key`, 1 to 4 Seats picked from the Seats it saw as free (60% of iterations), 1 to 4 General Admission places (30%), or 2 Seats plus 1 to 4 places (10%). Once every Seat is taken, it picks from all Seats, like a client with a seat map that's a moment out of date. Customers who read the same availability pick from the same free Seats, so they collide.
3. If the Hold succeeds, the Customer keeps it (40%), retries the same request with the same key and checks that the same Hold comes back (20%), or releases it (40%). The next iteration's Hold replaces the Customer's previous one. So inventory keeps changing hands, and the race continues after the Show first sells out.
4. Waits up to 0.5 s.

Thresholds fail the run if any response is a `5xx` or a connection error, if any check fails, or if the post-run check finds a violation. A `409` is an expected answer, not a failure.

**Post-run check.** Teardown reads every Customer's active Hold (`GET /shows/{id}/holds/mine`) and the Show's availability. It asserts the following:

- No Seat appears in two active Holds.
- A Seat shows as unavailable exactly when an active Hold contains it.
- The General Admission Section's `available` isn't negative, and `available` plus the places in active Holds equals its capacity.

The run is shorter than the 10-minute Hold time, so no Hold expires during it.

## Results

| | |
|---|---|
| Requests | 79,098, about **694 per second** over the whole run (1 min 54 s including setup and teardown) |
| `5xx` responses and connection errors | **0** |
| Hold attempts with a fresh key | 36,903 |
| Hold created (`201`) | **6,714** |
| Nothing held, `409 inventory-unavailable` | **30,189** |
| Retries with the same key | 1,371, and each one returned the original Hold |
| Releases | 2,705 (one of them retried once after a `409 conflict`) |
| Checks | 78,182 of 78,182 passed |

**Latency**

| Endpoint | p50 | p95 | p99 | max |
|---|---|---|---|---|
| `POST /shows/{id}/holds` | 8.4 ms | 272 ms | 1.68 s | 7.24 s |
| `GET /shows/{id}/availability` | 4.6 ms | 1.97 s | 3.02 s | 7.15 s |
| `POST /holds/{id}/release` | 7.3 ms | 269 ms | 2.01 s | 4.06 s |
| All requests | 6.9 ms | 1.24 s | 2.47 s | 7.24 s |

**Post-run check: passed.** 137 Customers had an active Hold. All 50 Seats were held, each by exactly one Hold, and 1 of the 200 General Admission places was left. That place, plus the places in active Holds, added up to exactly the capacity. `invariant_violations` was 0.

## Observations

- Most Hold attempts ended in `409`, which is expected. 300 Customers competed for 250 tickets, and many of them requested Seats that someone had just taken. Every `409` had the `inventory-unavailable` type and nothing was held.
- The tails are much slower than the medians. At 300 virtual users, k6, the backend, PostgreSQL and Keycloak all share one laptop's CPUs, and the backend has only the default 10 database connections. So requests queue for a connection, and Holds for the same Seats queue for the same row locks. None of this was tuned, and none of it affected correctness.
- The race sometimes produces a deadlock. For example, a Hold that replaces a Customer's previous one gives back the old Seats and then claims the new ones, while another Customer's release goes through some of the same rows. PostgreSQL aborts one of the transactions and the API answers with a retryable `409`, as the [API reference](api.md#holds) describes. In this run, 1 of 2,705 releases hit this and succeeded on its retry.
