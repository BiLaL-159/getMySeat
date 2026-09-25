package com.getmyseat.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** An Organizer scheduling Shows of their Event and editing them while they're drafts. */
@ApiIntegrationTest
class ShowApiIT {

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
	void anOrganizerSchedulesADraftShowOfTheirEvent() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		Instant startsAt = ShowApi.inDays(30);

		RestTestClient.ResponseSpec response = api.schedule(event, token, ShowApi.show(venue, startsAt))
			.expectStatus()
			.isCreated();
		JsonNode show = ShowApi.read(response);

		assertThat(show.path("id").asString()).isNotBlank();
		assertThat(show.path("eventId").asString()).isEqualTo(event);
		assertThat(show.path("venueId").asString()).isEqualTo(venue);
		assertThat(Instant.parse(show.path("startsAt").asString())).isEqualTo(startsAt);
		assertThat(show.path("status").asString()).isEqualTo("DRAFT");
		assertThat(show.path("prices").isEmpty()).isTrue();
		assertThat(show.path("publishedAt").isNull()).isTrue();
		assertThat(show.path("version").asLong()).isZero();
	}

	@Test
	void theCreatedLocationPointsAtTheShow() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String location = api.schedule(event, token, ShowApi.show(api.approvedVenue(), ShowApi.inDays(7)))
			.expectStatus()
			.isCreated()
			.returnResult(String.class)
			.getResponseHeaders()
			.getLocation()
			.toString();

		api.get(location, token).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("DRAFT");
	}

	@Test
	void aShowCanOnlyBeScheduledAtAnApprovedVenue() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String draftVenue = api.venues.draftWithLayout(token);
		String pendingVenue = api.venues.draftWithLayout(token);
		api.venues.submit(pendingVenue, token).expectStatus().isOk();

		for (String venue : List.of(draftVenue, pendingVenue, "00000000-0000-0000-0000-000000000000")) {
			EventApi.assertValidationProblem(api.schedule(event, token, ShowApi.show(venue, ShowApi.inDays(7))),
					"venueId");
		}
	}

	@Test
	void aShowStartsInTheFuture() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);

		EventApi.assertValidationProblem(
				api.schedule(event, token, ShowApi.show(api.approvedVenue(), ShowApi.inDays(-1))), "startsAt");
	}

	@Test
	void aShowNeedsAVenueAndAStartTime() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);

		api.schedule(event, token, "{}")
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.errors[?(@.field == 'venueId')]")
			.isNotEmpty()
			.jsonPath("$.errors[?(@.field == 'startsAt')]")
			.isNotEmpty();
	}

	@Test
	void anOrganizerMovesTheirDraftShow() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);
		Instant later = ShowApi.inDays(60);

		JsonNode moved = ShowApi.read(api.change(show, token, ShowApi.edit(venue, later, 0)).expectStatus().isOk());

		assertThat(Instant.parse(moved.path("startsAt").asString())).isEqualTo(later);
		assertThat(moved.path("version").asLong()).isEqualTo(1);
	}

	@Test
	void movingAShowToAnotherVenueDropsPricesThatNoLongerApply() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.priced(api.draftShow(event, token, venue), token, venue);
		String otherVenue = api.approvedVenue();

		JsonNode moved = ShowApi
			.read(api.change(show, token, ShowApi.edit(otherVenue, ShowApi.inDays(7), 1)).expectStatus().isOk());

		assertThat(moved.path("venueId").asString()).isEqualTo(otherVenue);
		assertThat(moved.path("prices").isEmpty()).isTrue();
	}

	@Test
	void keepingTheVenueKeepsThePrices() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.priced(api.draftShow(event, token, venue), token, venue);

		JsonNode moved = ShowApi
			.read(api.change(show, token, ShowApi.edit(venue, ShowApi.inDays(9), 1)).expectStatus().isOk());

		assertThat(moved.path("prices").size()).isEqualTo(2);
	}

	@Test
	void aShowCanOnlyMoveToAnApprovedVenueAndAFutureTime() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);

		EventApi.assertValidationProblem(
				api.change(show, token, ShowApi.edit(api.venues.draftWithLayout(token), ShowApi.inDays(7), 0)),
				"venueId");
		EventApi.assertValidationProblem(api.change(show, token, ShowApi.edit(venue, ShowApi.inDays(-1), 0)),
				"startsAt");
	}

	@Test
	void anEditBasedOnAStaleVersionIsAConflict() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);
		api.change(show, token, ShowApi.edit(venue, ShowApi.inDays(10), 0)).expectStatus().isOk();

		ShowApi.assertConflict(api.change(show, token, ShowApi.edit(venue, ShowApi.inDays(20), 0)),
				"This Show was changed by someone else just now. Reload it and try again.");
	}

	@Test
	void anEditMustSayWhichVersionItChanges() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);

		EventApi.assertValidationProblem(api.change(show, token, ShowApi.show(venue, ShowApi.inDays(7))), "version");
	}

	@Test
	void anOrganizerPricesEverySectionOfTheirShowAtOnce() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		List<String> sections = api.sections(venue);
		String show = api.draftShow(event, token, venue);

		JsonNode priced = ShowApi.read(
				api.setPrices(show, token, ShowApi.prices(sections, 150_000, 99_900)).expectStatus().isOk());

		assertThat(priced.path("prices").valueStream()
			.map(p -> p.path("sectionId").asString() + "=" + p.path("amountPaise").asLong() + " "
					+ p.path("currency").asString()))
			.containsExactlyInAnyOrder(sections.get(0) + "=150000 INR", sections.get(1) + "=99900 INR");

		JsonNode repriced = ShowApi.read(api.setPrices(show, token, ShowApi.prices(sections.subList(1, 2), 120_000))
			.expectStatus()
			.isOk());
		assertThat(repriced.path("prices").size()).isEqualTo(1);
		assertThat(repriced.path("prices").path(0).path("amountPaise").asLong()).isEqualTo(120_000);

		api.get("/api/v1/shows/" + show, token)
			.expectBody()
			.jsonPath("$.prices.length()")
			.isEqualTo(1)
			.jsonPath("$.prices[0].sectionId")
			.isEqualTo(sections.get(1));
	}

	@Test
	void pricesAreWholePaiseAboveZero() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String section = api.sections(venue).get(0);
		String show = api.draftShow(event, token, venue);

		EventApi.assertValidationProblem(api.setPrices(show, token, ShowApi.prices(List.of(section), 0)),
				"prices[0].amountPaise");
		EventApi.assertValidationProblem(api.setPrices(show, token, ShowApi.prices(List.of(section), -100)),
				"prices[0].amountPaise");
		api.setPrices(show, token, """
				{ "prices": [ { "sectionId": "%s", "amountPaise": 499.5, "currency": "INR" } ] }
				""".formatted(section)).expectStatus().isBadRequest();
	}

	@Test
	void pricesAreInRupees() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);

		EventApi.assertValidationProblem(
				api.setPrices(show, token, ShowApi.prices(api.sections(venue).subList(0, 1), 50_000).replace("INR", "USD")),
				"prices[0].currency");
	}

	@Test
	void onlySectionsOfTheShowsVenueCanBePriced() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);
		String elsewhere = api.sections(api.approvedVenue()).get(0);

		EventApi.assertValidationProblem(
				api.setPrices(show, token, ShowApi.prices(List.of(api.sections(venue).get(0), elsewhere), 100, 100)),
				"prices[1].sectionId");
		api.get("/api/v1/shows/" + show, token).expectBody().jsonPath("$.prices.length()").isEqualTo(0);
	}

	@Test
	void aSectionHasOnePricePerShow() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String section = api.sections(venue).get(0);
		String show = api.draftShow(event, token, venue);

		EventApi.assertValidationProblem(api.setPrices(show, token, ShowApi.prices(List.of(section, section), 100, 200)),
				"prices[1].sectionId");
	}

	@Test
	void anOrganizerListsTheShowsOfTheirEventIncludingDraftsSoonestFirst() {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenue();
		String later = ShowApi.id(api.schedule(event, token, ShowApi.show(venue, ShowApi.inDays(20))).expectStatus().isCreated());
		String sooner = api.priced(
				ShowApi.id(api.schedule(event, token, ShowApi.show(venue, ShowApi.inDays(10))).expectStatus().isCreated()),
				token, venue);
		api.publish(sooner, token).expectStatus().isOk();
		api.draftShow(api.events.createEvent(token), token, venue);

		JsonNode page = ShowApi.read(api.get("/api/v1/events/" + event + "/shows", token).expectStatus().isOk());

		assertThat(page.path("content").valueStream().map(s -> s.path("id").asString())).containsExactly(sooner, later);
		assertThat(page.path("content").valueStream().map(s -> s.path("status").asString())).containsExactly("PUBLISHED",
				"DRAFT");
		assertThat(page.path("page").path("totalElements").asLong()).isEqualTo(2);
	}

	@Test
	void theShowsOfAnEventCanOnlyBeSortedByStartOrCreationTime() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);

		EventApi.assertValidationProblem(api.get("/api/v1/events/" + event + "/shows?sort=venueId", token), "sort");
	}

	@Test
	void onlyOrganizersManageShows() {
		String owner = jwts.organizer().encode();
		String event = api.events.createEvent(owner);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, owner, venue);
		String customer = jwts.customer().encode();
		String body = ShowApi.edit(venue, ShowApi.inDays(7), 0);
		String prices = ShowApi.prices(List.of(), new long[0]);

		api.schedule(event, customer, body).expectStatus().isForbidden();
		api.change(show, customer, body).expectStatus().isForbidden();
		api.setPrices(show, customer, prices).expectStatus().isForbidden();
		api.publish(show, customer).expectStatus().isForbidden();

		api.schedule(event, null, body).expectStatus().isUnauthorized();
		api.change(show, null, body).expectStatus().isUnauthorized();
		api.setPrices(show, null, prices).expectStatus().isUnauthorized();
		api.publish(show, null).expectStatus().isUnauthorized();
	}

}
