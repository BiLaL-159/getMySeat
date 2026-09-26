package com.getmyseat.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Swagger UI documents every endpoint with its request and response schemas. */
@ApiIntegrationTest
class OpenApiDocsIT {

	/** Every operation, as {@code method path}. */
	static final List<String> ENDPOINTS = List.of("get /api/v1/me", "post /api/v1/organizer-applications",
			"get /api/v1/organizer-applications/mine", "get /api/v1/admin/organizer-applications",
			"post /api/v1/admin/organizer-applications/{id}/approve",
			"post /api/v1/admin/organizer-applications/{id}/reject", "post /api/v1/venues", "put /api/v1/venues/{id}",
			"post /api/v1/venues/{id}/submit", "get /api/v1/venues/mine", "get /api/v1/venues",
			"get /api/v1/venues/{id}", "post /api/v1/venues/{venueId}/sections",
			"put /api/v1/venues/{venueId}/sections/{sectionId}", "delete /api/v1/venues/{venueId}/sections/{sectionId}",
			"post /api/v1/venues/{venueId}/sections/{sectionId}/seats",
			"delete /api/v1/venues/{venueId}/sections/{sectionId}/seats/{seatId}", "get /api/v1/admin/venues",
			"post /api/v1/admin/venues/{id}/approve", "post /api/v1/admin/venues/{id}/reject", "post /api/v1/events",
			"put /api/v1/events/{id}", "post /api/v1/events/{id}/publish", "get /api/v1/events/mine",
			"get /api/v1/events", "get /api/v1/events/{id}", "post /api/v1/events/{eventId}/shows",
			"put /api/v1/shows/{id}", "put /api/v1/shows/{id}/prices", "post /api/v1/shows/{id}/publish",
			"get /api/v1/events/{eventId}/shows", "get /api/v1/shows/{id}",
			"get /api/v1/shows/{id}/availability", "post /api/v1/shows/{id}/holds", "get /api/v1/holds/{id}",
			"post /api/v1/holds/{id}/release", "get /api/v1/shows/{id}/holds/mine");

	@Autowired
	RestTestClient client;

	@Test
	void everyEndpointIsDocumentedWithItsSchemas() {
		JsonNode spec = spec();

		for (String endpoint : ENDPOINTS) {
			String[] parts = endpoint.split(" ");
			JsonNode operation = spec.path("paths").path(parts[1]).path(parts[0]);
			assertThat(operation.isObject()).as(endpoint).isTrue();
			assertThat(operation.path("summary").asString()).as(endpoint + " summary").isNotBlank();
			assertThat(operation.path("parameters").valueStream().map(p -> p.path("name").asString()))
				.as(endpoint + " parameters")
				.doesNotContain("caller", "organizer", "admin", "customer", "pageable");
			if (!parts[0].equals("get") && !parts[0].equals("delete") && !parts[1].endsWith("/publish")
					&& !parts[1].endsWith("/release") && !parts[1].endsWith("/submit") && !parts[1].endsWith("/approve")) {
				assertThat(operation.at("/requestBody/content/application~1json/schema").isObject())
					.as(endpoint + " request schema")
					.isTrue();
			}
			if (!parts[0].equals("delete")) {
				JsonNode ok = operation.path("responses").path(parts[0].equals("post") ? "201" : "200");
				if (ok.isMissingNode()) {
					ok = operation.path("responses").path("200");
				}
				assertThat(ok.path("content").valueStream().anyMatch(media -> media.path("schema").isObject()))
					.as(endpoint + " response schema")
					.isTrue();
			}
		}
	}

	@Test
	void pagedEndpointsDocumentPageSizeAndSort() {
		JsonNode operation = spec().path("paths").path("/api/v1/events").path("get");

		assertThat(operation.path("parameters").valueStream().map(p -> p.path("name").asString()))
			.contains("q", "city", "category", "from", "to", "page", "size", "sort");
	}

	@Test
	void responsesWithTheSameNameDoNotShareASchema() {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.path("VenueSummary").path("properties").has("status")).isTrue();
		assertThat(schemas.path("ShowVenue").path("properties").has("timeZone")).isTrue();
	}

	@Test
	void holdCreationDocumentsTheUnavailableInventoryProblem() {
		JsonNode spec = spec();
		JsonNode conflict = spec.at("/paths/~1api~1v1~1shows~1{id}~1holds/post/responses/409");

		assertThat(conflict.path("description").asString()).contains("urn:getmyseat:problem:inventory-unavailable");
		String schema = conflict.at("/content/application~1problem+json/schema/$ref").asString();
		JsonNode properties = spec.at("/components/schemas/" + schema.substring(schema.lastIndexOf('/') + 1))
			.path("properties");
		assertThat(properties.has("unavailableSeats")).isTrue();
		assertThat(properties.path("unavailableSections").isObject()).isTrue();
		assertThat(spec.at("/components/schemas/HoldResponse/properties/status/enum").valueStream().map(JsonNode::asString))
			.contains("ACTIVE");
	}

	@Test
	void holdCreationDocumentsTheIdempotencyKey() {
		JsonNode operation = spec().at("/paths/~1api~1v1~1shows~1{id}~1holds/post");

		JsonNode header = operation.path("parameters")
			.valueStream()
			.filter(p -> p.path("name").asString().equals("Idempotency-Key"))
			.findFirst()
			.orElseThrow();
		assertThat(header.path("in").asString()).isEqualTo("header");
		assertThat(header.path("required").asBoolean()).isTrue();
		assertThat(header.path("description").asString()).isNotBlank();
		assertThat(header.at("/schema/minLength").asInt()).isEqualTo(1);
		assertThat(header.at("/schema/maxLength").asInt()).isEqualTo(255);
		assertThat(operation.at("/responses/409/description").asString())
			.contains("urn:getmyseat:problem:idempotency-key-reused");
		assertThat(operation.at("/responses/400/description").asString()).contains("Idempotency-Key");
	}

	@Test
	void holdAndAvailabilityEndpointsDocumentTheirProblems() {
		JsonNode paths = spec().path("paths");

		assertThat(paths.at("/~1api~1v1~1shows~1{id}~1availability/get/responses/404/description").asString())
			.isNotBlank();
		JsonNode create = paths.at("/~1api~1v1~1shows~1{id}~1holds/post/responses");
		assertThat(create.path("404").path("description").asString()).isNotBlank();
		assertThat(create.path("409").path("description").asString()).contains("urn:getmyseat:problem:conflict");
		for (String hold : List.of("/~1api~1v1~1holds~1{id}/get", "/~1api~1v1~1holds~1{id}~1release/post",
				"/~1api~1v1~1shows~1{id}~1holds~1mine/get")) {
			assertThat(paths.at(hold + "/responses/404/content").has("application/problem+json")).as(hold).isTrue();
			assertThat(paths.at(hold + "/responses/409/content").has("application/problem+json")).as(hold).isTrue();
		}
	}

	private JsonNode spec() {
		return JsonMapper.builder()
			.build()
			.readTree(client.get().uri("/v3/api-docs").exchange().expectBody().returnResult().getResponseBody());
	}

}
