package com.getmyseat.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.catalogue.ShowApi;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** Calls the Hold API the way a client would, for tests that need Shows to hold tickets at, and Holds. */
final class HoldApi {

	/** The Stalls' Section Price, ₹750. */
	static final long STALLS_PAISE = 75_000;

	/** The Standing Section Price, ₹400. */
	static final long STANDING_PAISE = 40_000;

	/**
	 * A published Show at a Venue with a Seated Section and a General Admission Section.
	 * @param stalls the Seated Section, with Seats A1, A2, B1 and B2
	 * @param seats the Stalls' Seat ids, in that order
	 * @param standing the General Admission Section, with room for 500 unless asked otherwise
	 */
	record SellableShow(String id, String stalls, List<String> seats, String standing) {
	}

	final RestTestClient client;

	final TestJwts jwts;

	final ShowApi shows;

	HoldApi(RestTestClient client, TestJwts jwts) {
		this.client = client;
		this.jwts = jwts;
		this.shows = new ShowApi(client, jwts);
	}

	/** A fresh published Show, with the Stalls at ₹750 and Standing at ₹400. */
	SellableShow publishedShow() {
		return publishedShow(500);
	}

	/** Like {@link #publishedShow()}, with room for the given number in the Standing Section. */
	SellableShow publishedShow(int standingCapacity) {
		String organizer = this.jwts.organizer().encode();
		String venue = this.shows.approvedVenue(standingCapacity);
		String show = this.shows.draftShow(this.shows.publishedEvent(organizer), organizer, venue);
		List<String> sections = this.shows.sections(venue);
		this.shows.setPrices(show, organizer, ShowApi.prices(sections, STALLS_PAISE, STANDING_PAISE))
			.expectStatus()
			.isOk();
		this.shows.publish(show, organizer).expectStatus().isOk();
		JsonNode detail = ShowApi.read(this.shows.get("/api/v1/shows/" + show, null).expectStatus().isOk());
		List<String> seats = new ArrayList<>();
		detail.at("/sections/0/seats").forEach(seat -> seats.add(seat.path("id").asString()));
		return new SellableShow(show, sections.get(0), seats, sections.get(1));
	}

	/** A Hold request body for the given Seats and General Admission items. */
	static String body(List<String> seats, String... generalAdmission) {
		return """
				{ "seats": [ %s ], "generalAdmission": [ %s ] }
				""".formatted(String.join(", ", seats.stream().map(seat -> "\"" + seat + "\"").toList()),
				String.join(", ", generalAdmission));
	}

	/** A General Admission item for {@link #body}. */
	static String places(String section, int quantity) {
		return "{ \"sectionId\": \"%s\", \"quantity\": %d }".formatted(section, quantity);
	}

	/** Holds with a fresh {@code Idempotency-Key}, as a client does for each new Hold it makes. */
	RestTestClient.ResponseSpec hold(String show, @Nullable String token, String body) {
		return hold(show, token, body, UUID.randomUUID().toString());
	}

	/** @param idempotencyKey {@code null} to leave the header out */
	RestTestClient.ResponseSpec hold(String show, @Nullable String token, String body, @Nullable String idempotencyKey) {
		RestTestClient.RequestBodySpec request = request("POST", "/api/v1/shows/" + show + "/holds", token);
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return request.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	/** Holds as the Customer with the given token and returns the Hold. */
	JsonNode held(String show, String token, String body) {
		return read(hold(show, token, body).expectStatus().isCreated());
	}

	RestTestClient.ResponseSpec get(String hold, @Nullable String token) {
		return send("GET", "/api/v1/holds/" + hold, token, "");
	}

	RestTestClient.ResponseSpec release(String hold, @Nullable String token) {
		return send("POST", "/api/v1/holds/" + hold + "/release", token, "");
	}

	RestTestClient.ResponseSpec mine(String show, @Nullable String token) {
		return send("GET", "/api/v1/shows/" + show + "/holds/mine", token, "");
	}

	JsonNode availability(String show) {
		return read(this.shows.get("/api/v1/shows/" + show + "/availability", null).expectStatus().isOk());
	}

	static JsonNode read(RestTestClient.ResponseSpec response) {
		return ShowApi.read(response);
	}

	private RestTestClient.ResponseSpec send(String method, String uri, @Nullable String token, String body) {
		RestTestClient.RequestBodySpec request = request(method, uri, token);
		if (!body.isEmpty()) {
			request.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		return request.exchange();
	}

	private RestTestClient.RequestBodySpec request(String method, String uri, @Nullable String token) {
		return this.client.method(HttpMethod.valueOf(method)).uri(uri).headers(h -> {
			if (token != null) {
				h.setBearerAuth(token);
			}
		});
	}

}
