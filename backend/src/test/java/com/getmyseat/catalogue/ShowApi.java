package com.getmyseat.catalogue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/**
 * Calls the Show API the way a client would, for tests that need Shows in a given state. Public so other modules' tests
 * can set up published Shows.
 */
public final class ShowApi {

	final EventApi events;

	final VenueApi venues;

	public ShowApi(RestTestClient client, TestJwts jwts) {
		this.events = new EventApi(client, jwts);
		this.venues = new VenueApi(client, jwts);
	}

	/** A start time the given number of days from now. */
	static Instant inDays(int days) {
		return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
	}

	static String show(String venue, Instant startsAt) {
		return "{ \"venueId\": \"%s\", \"startsAt\": \"%s\" }".formatted(venue, startsAt);
	}

	static String edit(String venue, Instant startsAt, long version) {
		return "{ \"venueId\": \"%s\", \"startsAt\": \"%s\", \"version\": %d }".formatted(venue, startsAt, version);
	}

	/** A price in INR for each Section, in the order given. */
	public static String prices(List<String> sections, long... amountsPaise) {
		List<String> prices = new ArrayList<>();
		for (int i = 0; i < sections.size(); i++) {
			prices.add("{ \"sectionId\": \"%s\", \"amountPaise\": %d, \"currency\": \"INR\" }".formatted(sections.get(i),
					amountsPaise[i]));
		}
		return "{ \"prices\": [ " + String.join(", ", prices) + " ] }";
	}

	/** An approved Venue, proposed by a fresh Organizer, with the layout from {@link VenueApi#withLayout}. */
	public String approvedVenue() {
		return approvedVenue(500);
	}

	/** Like {@link #approvedVenue()}, with room for the given number in the General Admission Section. */
	public String approvedVenue(int standingCapacity) {
		String owner = this.venues.jwts.organizer().encode();
		return this.venues.approve(owner,
				this.venues.withLayout(owner, this.venues.createVenue(owner), standingCapacity));
	}

	/** Like {@link #approvedVenue()}, in the given city and IANA time zone. */
	String approvedVenueIn(String city, String timeZone) {
		String owner = this.venues.jwts.organizer().encode();
		String venue = this.venues.createVenue(owner, """
				{ "name": "Town Hall", "address": "1 Main Road", "city": "%s", "timeZone": "%s" }
				""".formatted(city, timeZone));
		return this.venues.approve(owner, this.venues.withLayout(owner, venue));
	}

	/** A fresh published Event of the Organizer with the given token. */
	public String publishedEvent(String token) {
		return this.events.publishedEvent(token, EventApi.EVENT);
	}

	/** The ids of the Venue's Sections, in layout order. */
	public List<String> sections(String venue) {
		List<String> ids = new ArrayList<>();
		VenueApi.read(this.venues.get("/api/v1/venues/" + venue, null).expectStatus().isOk())
			.path("sections")
			.forEach(section -> ids.add(section.path("id").asString()));
		return ids;
	}

	RestTestClient.ResponseSpec schedule(String event, String token, String body) {
		return this.events.send("POST", "/api/v1/events/" + event + "/shows", token, body);
	}

	/** A draft Show of the Event at the Venue, a week from now. */
	public String draftShow(String event, String token, String venue) {
		return id(schedule(event, token, show(venue, inDays(7))).expectStatus().isCreated());
	}

	public RestTestClient.ResponseSpec setPrices(String show, String token, String body) {
		return this.events.send("PUT", "/api/v1/shows/" + show + "/prices", token, body);
	}

	/** Prices every Section of the Venue at ₹500 and returns the Show. */
	public String priced(String show, String token, String venue) {
		List<String> sections = sections(venue);
		long[] amounts = new long[sections.size()];
		Arrays.fill(amounts, 50_000);
		setPrices(show, token, prices(sections, amounts)).expectStatus().isOk();
		return show;
	}

	public RestTestClient.ResponseSpec publish(String show, String token) {
		return this.events.send("POST", "/api/v1/shows/" + show + "/publish", token, "");
	}

	/** A published Show of a fresh published Event, owned by the Organizer with the given token. */
	public String publishedShow(String token, String venue) {
		String event = this.events.publishedEvent(token, EventApi.EVENT);
		String show = priced(draftShow(event, token, venue), token, venue);
		publish(show, token).expectStatus().isOk();
		return show;
	}

	/** A priced, published Show of the given published Event, starting at the given time. */
	String publishedShow(String event, String token, String venue, Instant startsAt) {
		String show = priced(id(schedule(event, token, show(venue, startsAt)).expectStatus().isCreated()), token, venue);
		publish(show, token).expectStatus().isOk();
		return show;
	}

	/** A published Show of the given published Event that has already started, which takes a few seconds. */
	String pastShow(String event, String token, String venue) throws InterruptedException {
		Instant soon = Instant.now().plus(3, ChronoUnit.SECONDS);
		String show = publishedShow(event, token, venue, soon);
		while (!Instant.now().isAfter(soon)) {
			Thread.sleep(100);
		}
		return show;
	}

	RestTestClient.ResponseSpec change(String show, String token, String body) {
		return this.events.send("PUT", "/api/v1/shows/" + show, token, body);
	}

	public RestTestClient.ResponseSpec get(String uri, @Nullable String token) {
		return this.events.get(uri, token);
	}

	public static JsonNode read(RestTestClient.ResponseSpec response) {
		return EventApi.read(response);
	}

	static String id(RestTestClient.ResponseSpec response) {
		return EventApi.id(response);
	}

	static void assertConflict(RestTestClient.ResponseSpec response, String detail) {
		response.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict")
			.jsonPath("$.detail")
			.isEqualTo(detail);
	}

}
