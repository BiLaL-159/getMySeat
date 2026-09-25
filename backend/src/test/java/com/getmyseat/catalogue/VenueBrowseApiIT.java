package com.getmyseat.catalogue;

import static com.getmyseat.catalogue.VenueApi.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** Anyone, signed in or not, finding approved Venues and viewing their layout. */
@ApiIntegrationTest
class VenueBrowseApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	VenueApi api;

	String owner;

	/** Makes names unique to this test run, so searches don't see other tests' Venues. */
	String tag;

	@BeforeEach
	void setUp() {
		api = new VenueApi(client, jwts);
		owner = jwts.organizer().encode();
		tag = UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void anyoneSearchesApprovedVenuesByNameAndCity() {
		String dome = approved("Dome " + tag, "Mumbai");
		String arena = approved("Arena " + tag, "Pune");
		String draft = api.withLayout(owner, api.createVenue(owner, venue("Dome Draft " + tag, "Mumbai")));
		String pending = api.withLayout(owner, api.createVenue(owner, venue("Dome Pending " + tag, "Mumbai")));
		api.submit(pending, owner).expectStatus().isOk();

		assertThat(search("?q=" + tag)).containsExactlyInAnyOrder(dome, arena).doesNotContain(draft, pending);
		assertThat(search("?q=dome " + tag.toUpperCase())).containsExactly(dome);
		assertThat(search("?q=" + tag + "&city=pune")).containsExactly(arena);
		assertThat(search("?q=" + tag + "&city=Pun")).isEmpty();
	}

	@Test
	void searchTreatsLikeWildcardsAsText() {
		approved("100% Hall " + tag, "Delhi");
		approved("Hall " + tag, "Delhi");

		assertThat(search("?q=100% Hall " + tag)).hasSize(1);
		assertThat(search("?q=_all " + tag)).isEmpty();
	}

	@Test
	void searchResultsArePaginatedAndSortable() {
		approved("B " + tag, "Chennai");
		approved("A " + tag, "Chennai");
		approved("C " + tag, "Chennai");

		JsonNode page = read(api.get("/api/v1/venues?q=" + tag + "&size=2", null).expectStatus().isOk());
		assertThat(page.path("content")).hasSize(2);
		assertThat(page.path("content").get(0).path("name").asString()).isEqualTo("A " + tag);
		assertThat(page.path("page").path("totalElements").asInt()).isEqualTo(3);

		JsonNode descending = read(api.get("/api/v1/venues?q=" + tag + "&sort=name,desc", null).expectStatus().isOk());
		assertThat(descending.path("content").get(0).path("name").asString()).isEqualTo("C " + tag);

		api.get("/api/v1/venues?sort=ownerSubject", null).expectStatus().isBadRequest();
	}

	@Test
	void anyoneSeesAnApprovedVenuesLayout() {
		String venue = approved("Dome " + tag, "Mumbai");

		JsonNode detail = read(api.get("/api/v1/venues/" + venue, null).expectStatus().isOk());
		assertThat(detail.path("status").asString()).isEqualTo("APPROVED");
		JsonNode stalls = detail.path("sections").get(0);
		assertThat(stalls.path("kind").asString()).isEqualTo("SEATED");
		assertThat(VenueApi.labels(stalls)).containsExactly("A1", "A2", "B1", "B2");
		JsonNode standing = detail.path("sections").get(1);
		assertThat(standing.path("kind").asString()).isEqualTo("GENERAL_ADMISSION");
		assertThat(standing.path("capacity").asInt()).isEqualTo(500);
		assertThat(standing.path("seats")).isEmpty();
	}

	@Test
	void thePublicDoesNotSeeWhoProposedOrApprovedAVenue() {
		String venue = approved("Dome " + tag, "Mumbai");

		for (String viewer : Arrays.asList(null, jwts.customer().encode(), jwts.organizer().encode())) {
			JsonNode detail = read(api.get("/api/v1/venues/" + venue, viewer).expectStatus().isOk());
			assertThat(detail.path("ownerSubject").isNull() || detail.path("ownerSubject").isMissingNode()).isTrue();
			assertThat(detail.path("decidedBy").isNull() || detail.path("decidedBy").isMissingNode()).isTrue();
		}
		assertThat(read(api.get("/api/v1/venues/" + venue, owner)).path("decidedBy").asString()).isNotEmpty();
		assertThat(read(api.get("/api/v1/venues/" + venue, jwts.admin().encode())).path("ownerSubject").asString())
			.isNotEmpty();
	}

	@Test
	void anUnapprovedVenueIsVisibleOnlyToItsOwnerAndAdmins() {
		String venue = api.draftWithLayout(owner);

		api.get("/api/v1/venues/" + venue, null).expectStatus().isNotFound();
		api.get("/api/v1/venues/" + venue, jwts.customer().encode()).expectStatus().isNotFound();
		api.get("/api/v1/venues/" + venue, owner).expectStatus().isOk();
		api.get("/api/v1/venues/" + venue, jwts.admin().encode()).expectStatus().isOk();
		api.get("/api/v1/venues/" + UUID.randomUUID(), null).expectStatus().isNotFound();
	}

	@Test
	void anInvalidTokenOnAPublicEndpointIsStillRejected() {
		api.get("/api/v1/venues", "not-a-jwt").expectStatus().isUnauthorized();
	}

	@Test
	void theOpenApiSpecDescribesThePublicVenueEndpointsWithoutTheCaller() {
		JsonNode spec = read(api.get("/v3/api-docs", null).expectStatus().isOk());
		JsonNode venue = spec.path("paths").path("/api/v1/venues/{id}").path("get");
		List<String> parameters = new ArrayList<>();
		venue.path("parameters").forEach(p -> parameters.add(p.path("name").asString()));
		assertThat(parameters).containsExactly("id");
		assertThat(venue.path("security").isArray()).as("public, so no security requirement").isTrue();
		assertThat(venue.path("security")).isEmpty();
		assertThat(spec.path("paths").path("/api/v1/me").path("get").path("parameters")).isEmpty();
		assertThat(spec.path("paths").path("/api/v1/venues/{venueId}/sections").has("post")).isTrue();
		assertThat(spec.path("paths").path("/api/v1/admin/venues/{id}/approve").has("post")).isTrue();
	}

	String approved(String name, String city) {
		return api.approve(owner, api.withLayout(owner, api.createVenue(owner, venue(name, city))));
	}

	static String venue(String name, String city) {
		return """
				{ "name": "%s", "address": "Main Road", "city": "%s", "timeZone": "Asia/Kolkata" }
				""".formatted(name, city);
	}

	/** @param query the query string as plain text; the client encodes it */
	List<String> search(String query) {
		JsonNode page = read(api.get("/api/v1/venues" + query, null).expectStatus().isOk());
		List<String> ids = new ArrayList<>();
		for (JsonNode venue : page.path("content")) {
			assertThat(venue.path("status").asString()).isEqualTo("APPROVED");
			assertThat(venue.has("sections")).isFalse();
			ids.add(venue.path("id").asString());
		}
		return ids;
	}

}
