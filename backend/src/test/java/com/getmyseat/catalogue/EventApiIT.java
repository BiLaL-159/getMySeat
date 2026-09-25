package com.getmyseat.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** An Organizer creating, editing, publishing and listing their own Events. */
@ApiIntegrationTest
class EventApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	EventApi api;

	@BeforeEach
	void setUp() {
		this.api = new EventApi(this.client, this.jwts);
	}

	@Test
	void anOrganizerCreatesADraftEvent() {
		TestJwts.Token organizer = jwts.organizer();

		RestTestClient.ResponseSpec response = api.send("POST", "/api/v1/events", organizer.encode(), EventApi.EVENT)
			.expectStatus()
			.isCreated();
		JsonNode event = EventApi.read(response);

		assertThat(event.path("id").asString()).isNotBlank();
		assertThat(event.path("title").asString()).isEqualTo("Coldplay: Music of the Spheres");
		assertThat(event.path("description").asString()).isEqualTo("The world tour comes to Mumbai.");
		assertThat(event.path("category").asString()).isEqualTo("MUSIC");
		assertThat(event.path("language").asString()).isEqualTo("en");
		assertThat(event.path("status").asString()).isEqualTo("DRAFT");
		assertThat(event.path("ownerSubject").asString()).isEqualTo(organizer.subject());
		assertThat(event.path("publishedAt").isNull()).isTrue();
		assertThat(event.path("version").asLong()).isZero();
	}

	@Test
	void theCreatedLocationPointsAtTheEvent() {
		String token = jwts.organizer().encode();
		String location = api.send("POST", "/api/v1/events", token, EventApi.EVENT)
			.expectStatus()
			.isCreated()
			.returnResult(String.class)
			.getResponseHeaders()
			.getLocation()
			.toString();

		api.get(location, token).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("DRAFT");
	}

	@Test
	void anOrganizerEditsTheirDraft() {
		String token = jwts.organizer().encode();
		String event = api.createEvent(token);

		JsonNode edited = EventApi.read(api.send("PUT", "/api/v1/events/" + event, token, """
				{ "title": "  Stand-up Night  ", "description": "An evening of comedy.",
				  "category": "COMEDY", "language": "HI", "version": 0 }
				""").expectStatus().isOk());

		assertThat(edited.path("title").asString()).isEqualTo("Stand-up Night");
		assertThat(edited.path("description").asString()).isEqualTo("An evening of comedy.");
		assertThat(edited.path("category").asString()).isEqualTo("COMEDY");
		assertThat(edited.path("language").asString()).isEqualTo("hi");
		assertThat(edited.path("status").asString()).isEqualTo("DRAFT");
		assertThat(edited.path("version").asLong()).isEqualTo(1);

		api.get("/api/v1/events/" + event, token).expectBody().jsonPath("$.title").isEqualTo("Stand-up Night");
	}

	@Test
	void anEditBasedOnAStaleVersionIsAConflict() {
		String token = jwts.organizer().encode();
		String event = api.createEvent(token);
		api.send("PUT", "/api/v1/events/" + event, token, EventApi.event("First edit", 0)).expectStatus().isOk();

		api.send("PUT", "/api/v1/events/" + event, token, EventApi.event("Second edit from an old tab", 0))
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict");

		api.get("/api/v1/events/" + event, token).expectBody().jsonPath("$.title").isEqualTo("First edit");
	}

	@Test
	void anOrganizerPublishesTheirDraft() {
		String token = jwts.organizer().encode();
		String event = api.createEvent(token);

		JsonNode published = EventApi.read(api.publish(event, token).expectStatus().isOk());

		assertThat(published.path("status").asString()).isEqualTo("PUBLISHED");
		assertThat(published.path("publishedAt").asString()).isNotBlank();
		assertThat(published.path("version").asLong()).isEqualTo(1);
	}

	@Test
	void anEventIsPublishedOnlyOnce() {
		String token = jwts.organizer().encode();
		String event = api.createEvent(token);
		api.publish(event, token).expectStatus().isOk();

		api.publish(event, token)
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.detail")
			.isEqualTo("This Event is already published.");
	}

	@Test
	void anOrganizerEditsTheirPublishedEvent() {
		String token = jwts.organizer().encode();
		String event = api.createEvent(token);
		api.publish(event, token).expectStatus().isOk();

		api.send("PUT", "/api/v1/events/" + event, token, EventApi.event("Updated after publishing", 1))
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("PUBLISHED")
			.jsonPath("$.title")
			.isEqualTo("Updated after publishing");
	}

	@Test
	void anotherOrganizersDraftIsNotFound() {
		String event = api.createEvent(jwts.organizer().encode());
		String other = jwts.organizer().encode();

		api.get("/api/v1/events/" + event, other).expectStatus().isNotFound();
		api.send("PUT", "/api/v1/events/" + event, other, EventApi.edit(0)).expectStatus().isNotFound();
		api.publish(event, other).expectStatus().isNotFound();
	}

	@Test
	void anotherOrganizerCannotChangeAPublishedEvent() {
		String owner = jwts.organizer().encode();
		String event = api.publishedEvent(owner, EventApi.EVENT);
		String other = jwts.organizer().encode();

		api.send("PUT", "/api/v1/events/" + event, other, EventApi.edit(1))
			.expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:forbidden");
		api.publish(event, other).expectStatus().isForbidden();

		api.get("/api/v1/events/" + event, null)
			.expectBody()
			.jsonPath("$.title")
			.isEqualTo("Coldplay: Music of the Spheres");
	}

	@Test
	void anUnknownEventIsNotFound() {
		String token = jwts.organizer().encode();
		String unknown = "/api/v1/events/00000000-0000-0000-0000-000000000000";

		api.get(unknown, null).expectStatus().isNotFound();
		api.send("PUT", unknown, token, EventApi.edit(0)).expectStatus().isNotFound();
		api.send("POST", unknown + "/publish", token, "").expectStatus().isNotFound();
	}

	@Test
	void anEventNeedsATitleDescriptionCategoryAndLanguage() {
		String token = jwts.organizer().encode();

		api.send("POST", "/api/v1/events", token, "{ \"title\": \" \" }")
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[?(@.field == 'title')]")
			.isNotEmpty()
			.jsonPath("$.errors[?(@.field == 'description')]")
			.isNotEmpty()
			.jsonPath("$.errors[?(@.field == 'category')]")
			.isNotEmpty()
			.jsonPath("$.errors[?(@.field == 'language')]")
			.isNotEmpty();
	}

	@Test
	void theTitleIsAtMostTwoHundredCharacters() {
		EventApi.assertValidationProblem(
				api.send("POST", "/api/v1/events", jwts.organizer().encode(), EventApi.event("x".repeat(201))),
				"title");
	}

	@Test
	void theLanguageIsAnIsoLanguageCode() {
		String token = jwts.organizer().encode();

		EventApi.assertValidationProblem(api.send("POST", "/api/v1/events", token,
				EventApi.EVENT.replace("\"en\"", "\"english\"")), "language");
		EventApi.assertValidationProblem(api.send("POST", "/api/v1/events", token,
				EventApi.EVENT.replace("\"en\"", "\"zz\"")), "language");
	}

	@Test
	void anUnknownCategoryIsRejected() {
		api.send("POST", "/api/v1/events", jwts.organizer().encode(), EventApi.EVENT.replace("MUSIC", "OPERA"))
			.expectStatus()
			.isBadRequest();
	}

	@Test
	void anEditMustSayWhichVersionItChanges() {
		String token = jwts.organizer().encode();
		String event = api.createEvent(token);

		EventApi.assertValidationProblem(api.send("PUT", "/api/v1/events/" + event, token, EventApi.EVENT),
				"version");
	}

	@Test
	void anOrganizerListsTheirOwnEventsIncludingDraftsNewestFirst() {
		TestJwts.Token organizer = jwts.organizer();
		String token = organizer.encode();
		String first = api.publishedEvent(token, EventApi.event("First"));
		String second = api.createEvent(token, EventApi.event("Second"));
		String third = api.createEvent(token, EventApi.event("Third"));
		api.createEvent(jwts.organizer().encode(), EventApi.event("Someone else's"));

		JsonNode page = EventApi.read(api.get("/api/v1/events/mine", token).expectStatus().isOk());

		assertThat(page.path("content").valueStream().map(e -> e.path("id").asString())).containsExactly(third, second,
				first);
		assertThat(page.path("content").valueStream().map(e -> e.path("status").asString()))
			.containsExactly("DRAFT", "DRAFT", "PUBLISHED");
		assertThat(page.path("content").path(0).path("ownerSubject").asString()).isEqualTo(organizer.subject());
		assertThat(page.path("page").path("totalElements").asLong()).isEqualTo(3);
	}

	@Test
	void anOrganizersEventsArePaginated() {
		String token = jwts.organizer().encode();
		for (String title : new String[] { "Delta", "Alpha", "Echo", "Charlie", "Bravo" }) {
			api.createEvent(token, EventApi.event(title));
		}

		JsonNode page = EventApi
			.read(api.get("/api/v1/events/mine?sort=title&size=2&page=1", token).expectStatus().isOk());

		assertThat(page.path("content").valueStream().map(e -> e.path("title").asString())).containsExactly("Charlie",
				"Delta");
		assertThat(page.path("page").path("number").asInt()).isEqualTo(1);
		assertThat(page.path("page").path("size").asInt()).isEqualTo(2);
		assertThat(page.path("page").path("totalElements").asLong()).isEqualTo(5);
		assertThat(page.path("page").path("totalPages").asInt()).isEqualTo(3);
	}

	@Test
	void theOwnListCanOnlyBeSortedByTitleOrCreationTime() {
		EventApi.assertValidationProblem(api.get("/api/v1/events/mine?sort=ownerSubject", jwts.organizer().encode()),
				"sort");
	}

	@Test
	void onlyOrganizersManageEvents() {
		String customer = jwts.customer().encode();
		String event = api.createEvent(jwts.organizer().encode());

		api.send("POST", "/api/v1/events", customer, EventApi.EVENT).expectStatus().isForbidden();
		api.send("PUT", "/api/v1/events/" + event, customer, EventApi.edit(0)).expectStatus().isForbidden();
		api.send("POST", "/api/v1/events/" + event + "/publish", customer, "").expectStatus().isForbidden();
		api.get("/api/v1/events/mine", customer).expectStatus().isForbidden();

		api.send("POST", "/api/v1/events", null, EventApi.EVENT).expectStatus().isUnauthorized();
		api.send("PUT", "/api/v1/events/" + event, null, EventApi.edit(0)).expectStatus().isUnauthorized();
		api.send("POST", "/api/v1/events/" + event + "/publish", null, "").expectStatus().isUnauthorized();
		api.get("/api/v1/events/mine", null).expectStatus().isUnauthorized();
	}

}
