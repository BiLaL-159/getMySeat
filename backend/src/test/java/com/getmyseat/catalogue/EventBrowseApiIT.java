package com.getmyseat.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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

	ShowApi shows;

	/** Makes this test's Events findable apart from those of other tests sharing the database. */
	String tag;

	@BeforeEach
	void setUp() {
		this.api = new EventApi(this.client, this.jwts);
		this.shows = new ShowApi(this.client, this.jwts);
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

	@Test
	void theCategoryFilterMatchesEventsOfThatCategory() {
		String token = jwts.organizer().encode();
		api.publishedEvent(token, EventApi.event("Music " + tag));
		String comedy = api.publishedEvent(token, """
				{ "title": "Stand-up %s", "description": "Jokes.", "category": "COMEDY", "language": "en" }
				""".formatted(tag));

		assertThat(ids(search("q=" + tag + "&category=COMEDY"))).containsExactly(comedy);
		EventApi.assertValidationProblem(api.get("/api/v1/events?category=OPERA", null), "category");
	}

	@Test
	void theCityFilterMatchesEventsWithAnUpcomingPublishedShowInThatCityIgnoringCase() {
		String token = jwts.organizer().encode();
		String city = "Pune-" + tag;
		String venue = shows.approvedVenueIn(city, "Asia/Kolkata");
		String elsewhere = shows.approvedVenue();
		String inCity = api.publishedEvent(token, EventApi.event("In city " + tag));
		shows.publishedShow(inCity, token, venue, ShowApi.inDays(10));
		String onlyDraftInCity = api.publishedEvent(token, EventApi.event("Draft show " + tag));
		shows.draftShow(onlyDraftInCity, token, venue);
		String otherCity = api.publishedEvent(token, EventApi.event("Other city " + tag));
		shows.publishedShow(otherCity, token, elsewhere, ShowApi.inDays(10));
		String noShows = api.publishedEvent(token, EventApi.event("No shows " + tag));

		assertThat(ids(search("q=" + tag + "&city=" + city.toUpperCase())))
			.containsExactly(inCity);
		assertThat(ids(search("q=" + tag))).contains(inCity, onlyDraftInCity, otherCity, noShows);
	}

	@Test
	void theCityFilterIgnoresShowsThatHaveStarted() throws InterruptedException {
		String token = jwts.organizer().encode();
		String city = "Goa-" + tag;
		String venue = shows.approvedVenueIn(city, "Asia/Kolkata");
		String event = api.publishedEvent(token, EventApi.event("Past " + tag));
		shows.pastShow(event, token, venue);

		assertThat(ids(search("city=" + city))).isEmpty();
	}

	@Test
	void theDateFiltersMatchEventsWithAnUpcomingPublishedShowOnThoseDatesInclusive() {
		String token = jwts.organizer().encode();
		String venue = shows.approvedVenueIn("Delhi-" + tag, "Asia/Kolkata");
		Instant tenDays = ShowApi.inDays(10);
		Instant twentyDays = ShowApi.inDays(20);
		String soon = api.publishedEvent(token, EventApi.event("Soon " + tag));
		shows.publishedShow(soon, token, venue, tenDays);
		String later = api.publishedEvent(token, EventApi.event("Later " + tag));
		shows.publishedShow(later, token, venue, twentyDays);
		LocalDate tenDaysLocal = localDate(tenDays, "Asia/Kolkata");
		LocalDate twentyDaysLocal = localDate(twentyDays, "Asia/Kolkata");

		assertThat(ids(search("q=" + tag + "&from=" + tenDaysLocal + "&to=" + tenDaysLocal))).containsExactly(soon);
		assertThat(ids(search("q=" + tag + "&from=" + tenDaysLocal.plusDays(1)))).containsExactly(later);
		assertThat(ids(search("q=" + tag + "&to=" + twentyDaysLocal.minusDays(1)))).containsExactly(soon);
		assertThat(ids(search("q=" + tag + "&from=" + tenDaysLocal + "&to=" + twentyDaysLocal)))
			.containsExactlyInAnyOrder(soon, later);
	}

	@Test
	void datesAreTheShowsLocalDateAtItsVenue() {
		String token = jwts.organizer().encode();
		String venue = shows.approvedVenueIn("Auckland-" + tag, "Pacific/Auckland");
		LocalDate utcDate = LocalDate.now(ZoneOffset.UTC).plusDays(30);
		Instant eveningUtc = LocalDateTime.of(utcDate, LocalTime.of(20, 0)).toInstant(ZoneOffset.UTC);
		String event = api.publishedEvent(token, EventApi.event("Late " + tag));
		shows.publishedShow(event, token, venue, eveningUtc);
		LocalDate aucklandDate = localDate(eveningUtc, "Pacific/Auckland");

		assertThat(aucklandDate).isEqualTo(utcDate.plusDays(1));
		assertThat(ids(search("q=" + tag + "&from=" + aucklandDate + "&to=" + aucklandDate))).containsExactly(event);
		assertThat(ids(search("q=" + tag + "&from=" + utcDate + "&to=" + utcDate))).isEmpty();
	}

	@Test
	void cityAndDatesMustMatchTheSameShow() {
		String token = jwts.organizer().encode();
		String city = "Kochi-" + tag;
		String venue = shows.approvedVenueIn(city, "Asia/Kolkata");
		String event = api.publishedEvent(token, EventApi.event("Split " + tag));
		shows.publishedShow(event, token, venue, ShowApi.inDays(10));
		Instant elsewhereLater = ShowApi.inDays(20);
		shows.publishedShow(event, token, shows.approvedVenue(), elsewhereLater);
		LocalDate laterDate = localDate(elsewhereLater, "Asia/Kolkata");

		assertThat(ids(search("city=" + city + "&from=" + laterDate + "&to=" + laterDate)))
			.isEmpty();
	}

	@Test
	void filtersCombineWithTheSearchText() {
		String token = jwts.organizer().encode();
		String city = "Jaipur-" + tag;
		String venue = shows.approvedVenueIn(city, "Asia/Kolkata");
		String match = api.publishedEvent(token, EventApi.event("Folk " + tag));
		shows.publishedShow(match, token, venue, ShowApi.inDays(10));
		String otherText = api.publishedEvent(token, EventApi.event("Unrelated title"));
		shows.publishedShow(otherText, token, venue, ShowApi.inDays(10));

		assertThat(ids(search("q=" + tag + "&city=" + city + "&category=MUSIC")))
			.containsExactly(match);
	}

	@Test
	void anEventWithManyMatchingShowsAppearsOnce() {
		String token = jwts.organizer().encode();
		String city = "Agra-" + tag;
		String venue = shows.approvedVenueIn(city, "Asia/Kolkata");
		String event = api.publishedEvent(token, EventApi.event("Twice " + tag));
		shows.publishedShow(event, token, venue, ShowApi.inDays(10));
		shows.publishedShow(event, token, venue, ShowApi.inDays(11));

		JsonNode page = search("city=" + city);

		assertThat(ids(page)).containsExactly(event);
		assertThat(page.path("page").path("totalElements").asLong()).isEqualTo(1);
	}

	@Test
	void theDateRangeMustNotEndBeforeItStarts() {
		EventApi.assertValidationProblem(api.get("/api/v1/events?from=2030-01-02&to=2030-01-01", null), "to");
		EventApi.assertValidationProblem(api.get("/api/v1/events?from=tomorrow", null), "from");
	}

	private JsonNode search(String query) {
		return EventApi.read(api.get("/api/v1/events?" + query, null).expectStatus().isOk());
	}

	private static List<String> ids(JsonNode page) {
		return page.path("content").valueStream().map(e -> e.path("id").asString()).toList();
	}

	private static LocalDate localDate(Instant instant, String zone) {
		return instant.atZone(ZoneId.of(zone)).toLocalDate();
	}

}
