package com.getmyseat.catalogue;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the Event API the way a client would, for tests that need Events in a given state. */
final class EventApi {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final String EVENT = """
			{ "title": "Coldplay: Music of the Spheres", "description": "The world tour comes to Mumbai.",
			  "category": "MUSIC", "language": "en" }
			""";

	final RestTestClient client;

	final TestJwts jwts;

	EventApi(RestTestClient client, TestJwts jwts) {
		this.client = client;
		this.jwts = jwts;
	}

	/** An edit that renames the Event, made against the given version. */
	static String edit(long version) {
		return event("Coldplay: Live at DY Patil", version);
	}

	static String event(String title, long version) {
		return """
				{ "title": "%s", "description": "The world tour comes to Mumbai.",
				  "category": "MUSIC", "language": "en", "version": %d }
				""".formatted(title, version);
	}

	static String event(String title) {
		return """
				{ "title": "%s", "description": "The world tour comes to Mumbai.",
				  "category": "MUSIC", "language": "en" }
				""".formatted(title);
	}

	String createEvent(String token) {
		return createEvent(token, EVENT);
	}

	String createEvent(String token, String body) {
		return id(send("POST", "/api/v1/events", token, body).expectStatus().isCreated());
	}

	/** Creates and publishes an Event as the given Organizer. */
	String publishedEvent(String token, String body) {
		String event = createEvent(token, body);
		publish(event, token).expectStatus().isOk();
		return event;
	}

	RestTestClient.ResponseSpec publish(String event, String token) {
		return send("POST", "/api/v1/events/" + event + "/publish", token, "");
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

}
