package com.getmyseat.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** Customers, signed in or not, browsing published Events. */
@ApiIntegrationTest
class EventBrowseApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	EventApi api;

	/** Makes this test's Events findable apart from those of other tests sharing the database. */
	String tag;

	@BeforeEach
	void setUp() {
		this.api = new EventApi(this.client, this.jwts);
		this.tag = "t" + UUID.randomUUID().toString().replace("-", "");
	}

	@Test
	void anyoneSearchesPublishedEventsWithoutSigningIn() {
		String token = jwts.organizer().encode();
		String published = api.publishedEvent(token, EventApi.event("Jazz Night " + tag));
		api.createEvent(token, EventApi.event("Jazz Draft " + tag));

		JsonNode page = EventApi.read(api.get("/api/v1/events?q=" + tag, null).expectStatus().isOk());

		assertThat(page.path("content").valueStream().map(e -> e.path("id").asString())).containsExactly(published);
		JsonNode event = page.path("content").path(0);
		assertThat(event.path("title").asString()).isEqualTo("Jazz Night " + tag);
		assertThat(event.path("status").asString()).isEqualTo("PUBLISHED");
		assertThat(event.path("ownerSubject").isNull()).isTrue();
	}

	@Test
	void theSearchMatchesPartOfTheTitleOrDescriptionIgnoringCase() {
		String token = jwts.organizer().encode();
		String byTitle = api.publishedEvent(token, EventApi.event("Rock " + tag.toUpperCase()));
		String byDescription = api.publishedEvent(token, """
				{ "title": "Symphony", "description": "Featuring %s strings.", "category": "MUSIC", "language": "en" }
				""".formatted(tag));
		api.publishedEvent(token, EventApi.event("Unrelated"));

		JsonNode page = EventApi.read(api.get("/api/v1/events?q=" + tag.substring(3, 20), null).expectStatus().isOk());

		assertThat(page.path("content").valueStream().map(e -> e.path("id").asString()))
			.containsExactlyInAnyOrder(byTitle, byDescription);
	}

	@Test
	void likeWildcardsInTheSearchAreMatchedLiterally() {
		String token = jwts.organizer().encode();
		api.publishedEvent(token, EventApi.event("Open Mic " + tag));

		JsonNode page = EventApi.read(api.get("/api/v1/events?q=" + tag.substring(0, 5) + "%25" + tag.substring(6), null)
			.expectStatus()
			.isOk());

		assertThat(page.path("content").isEmpty()).isTrue();
	}

	@Test
	void publishedEventsAreNewestFirstAndPaginated() {
		String token = jwts.organizer().encode();
		String first = api.publishedEvent(token, EventApi.event("One " + tag));
		String second = api.publishedEvent(token, EventApi.event("Two " + tag));
		String third = api.publishedEvent(token, EventApi.event("Three " + tag));

		JsonNode firstPage = EventApi.read(api.get("/api/v1/events?q=" + tag + "&size=2", null).expectStatus().isOk());
		JsonNode secondPage = EventApi
			.read(api.get("/api/v1/events?q=" + tag + "&size=2&page=1", null).expectStatus().isOk());

		assertThat(firstPage.path("content").valueStream().map(e -> e.path("id").asString())).containsExactly(third,
				second);
		assertThat(secondPage.path("content").valueStream().map(e -> e.path("id").asString())).containsExactly(first);
		assertThat(firstPage.path("page").path("totalElements").asLong()).isEqualTo(3);
		assertThat(firstPage.path("page").path("totalPages").asInt()).isEqualTo(2);
	}

	@Test
	void thePublicListCanOnlyBeSortedByTitleOrPublicationTime() {
		api.get("/api/v1/events?sort=title", null).expectStatus().isOk();
		EventApi.assertValidationProblem(api.get("/api/v1/events?sort=createdAt", null), "sort");
	}

	@Test
	void anyoneSeesAPublishedEvent() {
		String event = api.publishedEvent(jwts.organizer().encode(), EventApi.EVENT);

		api.get("/api/v1/events/" + event, null)
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("PUBLISHED")
			.jsonPath("$.ownerSubject")
			.isEmpty();
		api.get("/api/v1/events/" + event, jwts.customer().encode()).expectStatus().isOk();
	}

	@Test
	void aDraftIsNotFoundForAnyoneButItsOwner() {
		TestJwts.Token owner = jwts.organizer();
		String event = api.createEvent(owner.encode());

		api.get("/api/v1/events/" + event, null).expectStatus().isNotFound();
		api.get("/api/v1/events/" + event, jwts.customer().encode()).expectStatus().isNotFound();
		api.get("/api/v1/events/" + event, jwts.admin().encode()).expectStatus().isNotFound();
		api.get("/api/v1/events/" + event, jwts.organizer().encode()).expectStatus().isNotFound();

		api.get("/api/v1/events/" + event, owner.encode())
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.ownerSubject")
			.isEqualTo(owner.subject());
	}

	@Test
	void anInvalidTokenIsRejectedEvenOnPublicReads() {
		api.get("/api/v1/events", jwts.customer().expired().encode()).expectStatus().isUnauthorized();
	}

	@Test
	void theOwnListIsNotPublic() {
		api.get("/api/v1/events/mine", null).expectStatus().isUnauthorized();
	}

}
