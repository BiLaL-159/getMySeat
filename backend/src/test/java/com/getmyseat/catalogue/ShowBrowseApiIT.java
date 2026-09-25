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

/** Customers, signed in or not, listing an Event's Shows and opening one to see what it costs. */
@ApiIntegrationTest
class ShowBrowseApiIT {

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
	void anyoneListsAnEventsUpcomingPublishedShowsSoonestFirstWithTheirVenue() {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenueIn("Bengaluru", "Asia/Kolkata");
		Instant later = ShowApi.inDays(20);
		Instant sooner = ShowApi.inDays(10);
		String laterShow = api.publishedShow(event, token, venue, later);
		String soonerShow = api.publishedShow(event, token, venue, sooner);
		api.draftShow(event, token, venue);

		JsonNode page = ShowApi.read(api.get("/api/v1/events/" + event + "/shows", null).expectStatus().isOk());

		assertThat(ids(page)).containsExactly(soonerShow, laterShow);
		JsonNode first = page.path("content").path(0);
		assertThat(Instant.parse(first.path("startsAt").asString())).isEqualTo(sooner);
		assertThat(first.path("status").asString()).isEqualTo("PUBLISHED");
		assertThat(first.path("venue").path("id").asString()).isEqualTo(venue);
		assertThat(first.path("venue").path("name").asString()).isEqualTo("Town Hall");
		assertThat(first.path("venue").path("city").asString()).isEqualTo("Bengaluru");
		assertThat(first.path("venue").path("timeZone").asString()).isEqualTo("Asia/Kolkata");
	}

	@Test
	void theShowsOfAnEventArePaginated() {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenue();
		String first = api.publishedShow(event, token, venue, ShowApi.inDays(10));
		String second = api.publishedShow(event, token, venue, ShowApi.inDays(11));
		String third = api.publishedShow(event, token, venue, ShowApi.inDays(12));

		JsonNode firstPage = ShowApi
			.read(api.get("/api/v1/events/" + event + "/shows?size=2", null).expectStatus().isOk());
		JsonNode secondPage = ShowApi
			.read(api.get("/api/v1/events/" + event + "/shows?size=2&page=1", null).expectStatus().isOk());

		assertThat(ids(firstPage)).containsExactly(first, second);
		assertThat(ids(secondPage)).containsExactly(third);
		assertThat(firstPage.path("page").path("totalElements").asLong()).isEqualTo(3);
	}

	@Test
	void showsThatHaveStartedAreNotListedPubliclyButCanStillBeOpened() throws InterruptedException {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenue();
		String past = api.pastShow(event, token, venue);
		String upcoming = api.publishedShow(event, token, venue, ShowApi.inDays(10));

		JsonNode page = ShowApi
			.read(api.get("/api/v1/events/" + event + "/shows", jwts.customer().encode()).expectStatus().isOk());

		assertThat(ids(page)).containsExactly(upcoming);
		JsonNode own = ShowApi.read(api.get("/api/v1/events/" + event + "/shows", token).expectStatus().isOk());
		assertThat(ids(own)).containsExactly(past, upcoming);
		api.get("/api/v1/shows/" + past, null).expectStatus().isOk();
	}

	@Test
	void anyoneOpensAShowToSeeItsVenueAndEachSectionWithItsPrice() {
		String token = jwts.organizer().encode();
		String event = api.events.publishedEvent(token, EventApi.EVENT);
		String venue = api.approvedVenueIn("Chennai", "Asia/Kolkata");
		List<String> sections = api.sections(venue);
		Instant startsAt = ShowApi.inDays(10);
		String show = ShowApi.id(api.schedule(event, token, ShowApi.show(venue, startsAt)).expectStatus().isCreated());
		api.setPrices(show, token, ShowApi.prices(sections, 250_000, 80_000)).expectStatus().isOk();
		api.publish(show, token).expectStatus().isOk();

		JsonNode detail = ShowApi.read(api.get("/api/v1/shows/" + show, null).expectStatus().isOk());

		assertThat(detail.path("id").asString()).isEqualTo(show);
		assertThat(detail.path("eventId").asString()).isEqualTo(event);
		assertThat(Instant.parse(detail.path("startsAt").asString())).isEqualTo(startsAt);
		assertThat(detail.path("status").asString()).isEqualTo("PUBLISHED");
		JsonNode venueNode = detail.path("venue");
		assertThat(venueNode.path("id").asString()).isEqualTo(venue);
		assertThat(venueNode.path("name").asString()).isEqualTo("Town Hall");
		assertThat(venueNode.path("address").asString()).isEqualTo("1 Main Road");
		assertThat(venueNode.path("city").asString()).isEqualTo("Chennai");
		assertThat(venueNode.path("timeZone").asString()).isEqualTo("Asia/Kolkata");

		JsonNode seated = detail.path("sections").path(0);
		assertThat(seated.path("id").asString()).isEqualTo(sections.get(0));
		assertThat(seated.path("name").asString()).isEqualTo("Stalls");
		assertThat(seated.path("kind").asString()).isEqualTo("SEATED");
		assertThat(seated.path("price").path("amountPaise").asLong()).isEqualTo(250_000);
		assertThat(seated.path("price").path("currency").asString()).isEqualTo("INR");
		assertThat(seated.path("price").has("sectionId")).isFalse();
		assertThat(seated.path("capacity").isNull()).isTrue();
		assertThat(VenueApi.labels(seated)).containsExactly("A1", "A2", "B1", "B2");
		assertThat(seated.path("seats").path(0).path("id").asString()).isNotBlank();
		assertThat(seated.path("seats").path(0).path("row").asString()).isEqualTo("A");
		assertThat(seated.path("seats").path(0).path("number").asInt()).isEqualTo(1);

		JsonNode standing = detail.path("sections").path(1);
		assertThat(standing.path("id").asString()).isEqualTo(sections.get(1));
		assertThat(standing.path("name").asString()).isEqualTo("Standing");
		assertThat(standing.path("kind").asString()).isEqualTo("GENERAL_ADMISSION");
		assertThat(standing.path("price").path("amountPaise").asLong()).isEqualTo(80_000);
		assertThat(standing.path("capacity").asInt()).isEqualTo(500);
		assertThat(standing.path("seats").isEmpty()).isTrue();
		assertThat(detail.has("prices")).isFalse();
	}

	@Test
	void theOwnerSeesADraftShowWithUnpricedSections() {
		String token = jwts.organizer().encode();
		String event = api.events.createEvent(token);
		String venue = api.approvedVenue();
		String show = api.draftShow(event, token, venue);
		api.setPrices(show, token, ShowApi.prices(api.sections(venue).subList(0, 1), 50_000)).expectStatus().isOk();

		JsonNode detail = ShowApi.read(api.get("/api/v1/shows/" + show, token).expectStatus().isOk());

		assertThat(detail.path("status").asString()).isEqualTo("DRAFT");
		assertThat(detail.path("sections").path(0).path("price").path("amountPaise").asLong()).isEqualTo(50_000);
		assertThat(detail.path("sections").path(1).path("price").isNull()).isTrue();
		api.get("/api/v1/shows/" + show, null).expectStatus().isNotFound();
		api.get("/api/v1/shows/" + show, jwts.customer().encode()).expectStatus().isNotFound();
	}

	private static List<String> ids(JsonNode page) {
		return page.path("content").valueStream().map(s -> s.path("id").asString()).toList();
	}

}
