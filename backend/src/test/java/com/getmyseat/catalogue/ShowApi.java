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

/** Calls the Show API the way a client would, for tests that need Shows in a given state. */
final class ShowApi {

	final EventApi events;

	final VenueApi venues;

	ShowApi(RestTestClient client, TestJwts jwts) {
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
	static String prices(List<String> sections, long... amountsPaise) {
		List<String> prices = new ArrayList<>();
		for (int i = 0; i < sections.size(); i++) {
			prices.add("{ \"sectionId\": \"%s\", \"amountPaise\": %d, \"currency\": \"INR\" }".formatted(sections.get(i),
					amountsPaise[i]));
		}
		return "{ \"prices\": [ " + String.join(", ", prices) + " ] }";
	}

	/** An approved Venue, proposed by a fresh Organizer, with the layout from {@link VenueApi#withLayout}. */
	String approvedVenue() {
		String owner = this.venues.jwts.organizer().encode();
		return this.venues.approve(owner, this.venues.draftWithLayout(owner));
	}

	/** The ids of the Venue's Sections, in layout order. */
	List<String> sections(String venue) {
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
	String draftShow(String event, String token, String venue) {
		return id(schedule(event, token, show(venue, inDays(7))).expectStatus().isCreated());
	}

	RestTestClient.ResponseSpec setPrices(String show, String token, String body) {
		return this.events.send("PUT", "/api/v1/shows/" + show + "/prices", token, body);
	}

	/** Prices every Section of the Venue at ₹500 and returns the Show. */
	String priced(String show, String token, String venue) {
		List<String> sections = sections(venue);
		long[] amounts = new long[sections.size()];
		Arrays.fill(amounts, 50_000);
		setPrices(show, token, prices(sections, amounts)).expectStatus().isOk();
		return show;
	}

	RestTestClient.ResponseSpec publish(String show, String token) {
		return this.events.send("POST", "/api/v1/shows/" + show + "/publish", token, "");
	}

	/** A published Show of a fresh published Event, owned by the Organizer with the given token. */
	String publishedShow(String token, String venue) {
		String event = this.events.publishedEvent(token, EventApi.EVENT);
		String show = priced(draftShow(event, token, venue), token, venue);
		publish(show, token).expectStatus().isOk();
		return show;
	}

	RestTestClient.ResponseSpec change(String show, String token, String body) {
		return this.events.send("PUT", "/api/v1/shows/" + show, token, body);
	}

	RestTestClient.ResponseSpec get(String uri, @Nullable String token) {
		return this.events.get(uri, token);
	}

	static JsonNode read(RestTestClient.ResponseSpec response) {
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
