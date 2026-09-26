# Configuration

The backend is configured through environment variables. Every default points at the `docker compose` stack, so local development needs none of them.

## Database

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/getmyseat` | JDBC URL of the PostgreSQL database |
| `DB_USERNAME` | `getmyseat` | Database user |
| `DB_PASSWORD` | `getmyseat` | Database password. **Dev only** |

## Token validation

| Variable | Default | Purpose |
|---|---|---|
| `JWT_ISSUER_URI` | `http://localhost:8180/realms/getmyseat` | The `iss` every access token must carry |
| `JWT_JWK_SET_URI` | `${JWT_ISSUER_URI}/protocol/openid-connect/certs` | Where signing keys are fetched (lazily, so the app boots without Keycloak) |
| `JWT_AUDIENCE` | `getmyseat-api` | The `aud` every access token must carry |

## Keycloak Admin API

Approving an Organizer Application grants the `ORGANIZER` realm role through the Keycloak Admin API, signed in as the `getmyseat-backend` client.

| Variable | Default | Purpose |
|---|---|---|
| `KEYCLOAK_URL` | `http://localhost:8180` | Keycloak's base URL, as the backend reaches it |
| `KEYCLOAK_REALM` | `getmyseat` | The realm roles are granted in |
| `KEYCLOAK_BACKEND_CLIENT_ID` | `getmyseat-backend` | The backend's confidential client |
| `KEYCLOAK_BACKEND_CLIENT_SECRET` | `getmyseat-backend-dev-secret` | That client's secret. **Dev only**; always set a real one elsewhere |
| `KEYCLOAK_TIMEOUT` | `5s` | Per request; a slower Keycloak fails the approval with `503` |

## Booking

| Variable | Default | Purpose |
|---|---|---|
| `HOLD_TIME` | `10m` | How long a Hold lasts before it expires. Any Spring duration, such as `90s` |
