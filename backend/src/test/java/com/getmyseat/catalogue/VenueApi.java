package com.getmyseat.catalogue;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the Venue API the way a client would, for tests that need Venues in a given state. */
final class VenueApi {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final String VENUE = """
			{ "name": "NSCI Dome", "address": "Lala Lajpatrai Marg, Worli", "city": "Mumbai",
			  "timeZone": "Asia/Kolkata" }
			""";

	final RestTestClient client;

	final TestJwts jwts;

	VenueApi(RestTestClient client, TestJwts jwts) {
		this.client = client;
		this.jwts = jwts;
	}

	String createVenue(String token) {
		return createVenue(token, VENUE);
	}

	String createVenue(String token, String body) {
		return id(send("POST", "/api/v1/venues", token, body).expectStatus().isCreated());
	}

	/** A draft Venue with a Seated Section (rows A and B, two Seats each) and a General Admission Section. */
	String draftWithLayout(String token) {
		return withLayout(token, createVenue(token));
	}

	String withLayout(String token, String venue) {
		addSection(venue, token, """
				{ "name": "Stalls", "kind": "SEATED",
				  "rows": [ { "label": "A", "seatCount": 2 }, { "label": "B", "seatCount": 2 } ] }
				""").expectStatus().isCreated();
		addSection(venue, token, "{ \"name\": \"Standing\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 500 }")
			.expectStatus()
			.isCreated();
		return venue;
	}

	/** Submits the Venue as its owner and approves it as a fresh Admin. */
	String approve(String token, String venue) {
		submit(venue, token).expectStatus().isOk();
		send("POST", "/api/v1/admin/venues/" + venue + "/approve", this.jwts.admin().encode(), "").expectStatus().isOk();
		return venue;
	}

	RestTestClient.ResponseSpec addSection(String venue, String token, String body) {
		return send("POST", "/api/v1/venues/" + venue + "/sections", token, body);
	}

	RestTestClient.ResponseSpec submit(String venue, String token) {
		return send("POST", "/api/v1/venues/" + venue + "/submit", token, "");
	}

	static String seatsUri(String venue, String section) {
		return "/api/v1/venues/" + venue + "/sections/" + section + "/seats";
	}

	RestTestClient.ResponseSpec send(String method, String uri, @Nullable String token, String body) {
		RestTestClient.RequestBodySpec request = this.client.method(HttpMethod.valueOf(method)).uri(uri).headers(h -> {
			if (token != null) {
				h.setBearerAuth(token);
			}
		});
		if (!body.isEmpty()) {
			request.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		return request.exchange();
	}

	RestTestClient.ResponseSpec get(String uri, @Nullable String token) {
		return send("GET", uri, token, "");
	}

	static JsonNode read(RestTestClient.ResponseSpec response) {
		return JSON.readTree(response.expectBody().returnResult().getResponseBody());
	}

	static String id(RestTestClient.ResponseSpec response) {
		return read(response).path("id").asString();
	}

	static void assertValidationProblem(RestTestClient.ResponseSpec response, String field) {
		response.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[0].field")
			.isEqualTo(field);
	}

	static List<String> labels(JsonNode section) {
		List<String> labels = new ArrayList<>();
		section.path("seats").forEach(seat -> labels.add(seat.path("label").asString()));
		return labels;
	}

	static List<String> seatIds(JsonNode venue) {
		List<String> ids = new ArrayList<>();
		venue.path("sections")
			.forEach(section -> section.path("seats").forEach(seat -> ids.add(seat.path("id").asString())));
		return ids;
	}

}
