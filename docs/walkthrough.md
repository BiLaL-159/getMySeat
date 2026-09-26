# Walkthrough: from Customer to published Show

This takes one Customer from signing up as an Organizer to a published, priced Show that anyone can browse. Start the stack as in the [Quickstart](../README.md#quickstart). You'll need `curl` and `jq`.

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
