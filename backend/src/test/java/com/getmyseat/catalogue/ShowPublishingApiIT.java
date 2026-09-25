package com.getmyseat.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** Publishing a Show, what's locked once it's published, and who can see and change it. */
@ApiIntegrationTest
class ShowPublishingApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	ShowApi api;

	@BeforeEach
	void setUp() {
		this.api = new ShowApi(this.client, this.jwts);
	}

	@Test
	void anOrganizerPublishesAPricedShowOfTheirPublishedEvent() {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenue();
		String show = api.priced(api.draftShow(event, token, venue), token, venue);

		JsonNode published = ShowApi.read(api.publish(show, token).expectStatus().isOk());

		assertThat(published.path("status").asString()).isEqualTo("PUBLISHED");
		assertThat(published.path("publishedAt").asString()).isNotBlank();
	}

	@Test
	void theEventMustBePublishedFirst() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.priced(api.draftShow(event, token, venue), token, venue);

		ShowApi.assertConflict(api.publish(show, token), "Publish the Event before publishing its Shows.");
	}

	@Test
	void everySectionMustHaveAPrice() {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);
		api.setPrices(show, token, ShowApi.prices(api.sections(venue).subList(0, 1), 50_000)).expectStatus().isOk();

		ShowApi.assertConflict(api.publish(show, token),
				"Set a Section Price for every Section of the Venue before publishing.");
	}

	@Test
	void theStartTimeMustStillBeInTheFuture() throws InterruptedException {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenue();
		Instant soon = Instant.now().plus(2, ChronoUnit.SECONDS);
		String show = api.priced(ShowApi.id(api.schedule(event, token, ShowApi.show(venue, soon)).expectStatus().isCreated()),
				token, venue);
		while (!Instant.now().isAfter(soon)) {
			Thread.sleep(100);
		}

		ShowApi.assertConflict(api.publish(show, token),
				"The Show's start time has passed. Move it to a future time first.");
	}

	@Test
	void aShowIsPublishedOnlyOnce() {
		String token = jwts.organizer().encode();
		String show = api.publishedShow(token, api.approvedVenue());

		ShowApi.assertConflict(api.publish(show, token), "This Show is already published.");
	}

	@Test
	void aPublishedShowsVenueStartTimeAndPricesAreLocked() {
		String token = jwts.organizer().encode();
		String venue = api.approvedVenue();
		String show = api.publishedShow(token, venue);
		String locked = "This Show is published, so its Venue, start time and prices can't change.";
		long version = ShowApi.read(api.get("/api/v1/shows/" + show, token)).path("version").asLong();

		ShowApi.assertConflict(api.change(show, token, ShowApi.edit(venue, ShowApi.inDays(40), version)), locked);
		ShowApi.assertConflict(api.setPrices(show, token, ShowApi.prices(api.sections(venue), 1, 1)), locked);
		ShowApi.assertConflict(api.change(show, token, ShowApi.edit(api.venues.draftWithLayout(token),
				ShowApi.inDays(40), version)), locked);
		ShowApi.assertConflict(api.setPrices(show, token, ShowApi.prices(api.sections(api.approvedVenue()), 1, 1)),
				locked);

		JsonNode unchanged = ShowApi.read(api.get("/api/v1/shows/" + show, null).expectStatus().isOk());
		assertThat(unchanged.path("sections").valueStream().map(s -> s.path("price").path("amountPaise").asLong()))
			.containsOnly(50_000L);
	}

	@Test
	void anotherOrganizersDraftShowIsNotFound() {
		String owner = jwts.organizer().encode();
		String event = api.events.publishedEvent(owner, EventApi.EVENT);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, owner, venue);
		String other = jwts.organizer().encode();

		api.get("/api/v1/shows/" + show, other).expectStatus().isNotFound();
		api.get("/api/v1/shows/" + show, null).expectStatus().isNotFound();
		api.change(show, other, ShowApi.edit(venue, ShowApi.inDays(7), 0)).expectStatus().isNotFound();
		api.setPrices(show, other, ShowApi.prices(List.of(), new long[0])).expectStatus().isNotFound();
		api.publish(show, other).expectStatus().isNotFound();
	}

	@Test
	void anotherOrganizerCannotChangeAPublishedShow() {
		String venue = api.approvedVenue();
		String show = api.publishedShow(jwts.organizer().encode(), venue);
		String other = jwts.organizer().encode();

		api.change(show, other, ShowApi.edit(venue, ShowApi.inDays(7), 1))
			.expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:forbidden");
		api.setPrices(show, other, ShowApi.prices(List.of(), new long[0])).expectStatus().isForbidden();
		api.publish(show, other).expectStatus().isForbidden();
	}

	@Test
	void anotherOrganizerCannotScheduleShowsOfYourEvent() {
		String owner = jwts.organizer().encode();
		String draft = api.events.createEvent(owner);
		String published = api.events.publishedEvent(owner, EventApi.EVENT);
		String venue = api.approvedVenue();
		String other = jwts.organizer().encode();

		api.schedule(draft, other, ShowApi.show(venue, ShowApi.inDays(7))).expectStatus().isNotFound();
		api.schedule(published, other, ShowApi.show(venue, ShowApi.inDays(7))).expectStatus().isForbidden();
	}

	@Test
	void anyoneSeesAPublishedShowButOnlyThePublishedShowsOfAnEvent() {
		String owner = jwts.organizer().encode();
		String event = api.events.publishedEvent(owner, EventApi.EVENT);
		String venue = api.approvedVenue();
		String published = api.priced(api.draftShow(event, owner, venue), owner, venue);
		api.publish(published, owner).expectStatus().isOk();
		api.draftShow(event, owner, venue);

		api.get("/api/v1/shows/" + published, null)
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("PUBLISHED");
		for (String token : new String[] { null, jwts.organizer().encode() }) {
			JsonNode page = ShowApi.read(api.get("/api/v1/events/" + event + "/shows", token).expectStatus().isOk());
			assertThat(page.path("content").valueStream().map(s -> s.path("id").asString())).containsExactly(published);
		}
	}

	@Test
	void theShowsOfADraftEventAreNotFoundToEveryoneButItsOwner() {
		String owner = jwts.organizer().encode();
		String event = api.events.createEvent(owner);

		api.get("/api/v1/events/" + event + "/shows", null).expectStatus().isNotFound();
		api.get("/api/v1/events/" + event + "/shows", jwts.organizer().encode()).expectStatus().isNotFound();
		api.get("/api/v1/events/" + event + "/shows", owner).expectStatus().isOk();
	}

	@Test
	void anUnknownShowIsNotFound() {
		String token = jwts.organizer().encode();
		String unknown = "00000000-0000-0000-0000-000000000000";

		api.get("/api/v1/shows/" + unknown, null).expectStatus().isNotFound();
		api.change(unknown, token, ShowApi.edit(unknown, ShowApi.inDays(7), 0)).expectStatus().isNotFound();
		api.setPrices(unknown, token, ShowApi.prices(List.of(), new long[0])).expectStatus().isNotFound();
		api.publish(unknown, token).expectStatus().isNotFound();
		api.schedule(unknown, token, ShowApi.show(unknown, ShowApi.inDays(7))).expectStatus().isNotFound();
		api.get("/api/v1/events/" + unknown + "/shows", null).expectStatus().isNotFound();
	}

}
