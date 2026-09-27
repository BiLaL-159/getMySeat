package com.getmyseat.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

/** Customers, signed in or not, listing the cities they can search Shows in. */
@ApiIntegrationTest
class CityBrowseApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	EventApi events;

	ShowApi shows;

	/** Makes this test's cities tell apart from those of other tests sharing the database. */
	String tag;

	@BeforeEach
	void setUp() {
		this.events = new EventApi(this.client, this.jwts);
		this.shows = new ShowApi(this.client, this.jwts);
		this.tag = "t" + UUID.randomUUID().toString().replace("-", "");
	}

	@Test
	void anyoneListsTheCitiesWithAnUpcomingPublishedShowOnceEachSorted() {
		String token = jwts.organizer().encode();
		String event = events.publishedEvent(token, EventApi.event("Tour " + tag));
		String pune = "Pune-" + tag;
		String bengaluru = "bengaluru-" + tag;
		String puneVenue = shows.approvedVenueIn(pune, "Asia/Kolkata");
		shows.publishedShow(event, token, puneVenue, ShowApi.inDays(10));
		shows.publishedShow(event, token, puneVenue, ShowApi.inDays(11));
		shows.publishedShow(event, token, shows.approvedVenueIn(pune.toUpperCase(), "Asia/Kolkata"),
				ShowApi.inDays(12));
		shows.publishedShow(event, token, shows.approvedVenueIn(bengaluru, "Asia/Kolkata"), ShowApi.inDays(10));

		List<String> cities = cities();

		assertThat(cities.stream().filter(city -> city.toLowerCase(Locale.ROOT).endsWith(tag)).toList())
			.usingElementComparator(String.CASE_INSENSITIVE_ORDER)
			.containsExactly(bengaluru, pune);
		assertThat(cities).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
	}

	@Test
	void citiesOfDraftOrPastShowsAreLeftOut() throws InterruptedException {
		String token = jwts.organizer().encode();
		String event = events.publishedEvent(token, EventApi.event("Leftovers " + tag));
		String draftCity = "Draft-" + tag;
		shows.draftShow(event, token, shows.approvedVenueIn(draftCity, "Asia/Kolkata"));
		String pastCity = "Past-" + tag;
		shows.pastShow(event, token, shows.approvedVenueIn(pastCity, "Asia/Kolkata"));

		assertThat(cities()).doesNotContain(draftCity, pastCity);
	}

	@Test
	void citiesOfUnpublishedEventsAreLeftOut() {
		String token = jwts.organizer().encode();
		String draftEvent = events.createEvent(token, EventApi.event("Unannounced " + tag));
		String city = "Hidden-" + tag;
		shows.draftShow(draftEvent, token, shows.approvedVenueIn(city, "Asia/Kolkata"));

		assertThat(cities()).doesNotContain(city);
	}

	@Test
	void anInvalidTokenIsRejectedEvenHere() {
		events.get("/api/v1/cities", jwts.customer().expired().encode()).expectStatus().isUnauthorized();
	}

	private List<String> cities() {
		return EventApi.read(events.get("/api/v1/cities", null).expectStatus().isOk())
			.valueStream()
			.map(city -> city.asString())
			.toList();
	}

}
