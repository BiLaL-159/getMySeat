package com.getmyseat.booking;

import static com.getmyseat.booking.HoldApi.body;
import static com.getmyseat.booking.HoldApi.places;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.booking.HoldApi.SellableShow;
import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestClock;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** A Customer holds Seats and General Admission places at a Show, all or nothing, and reads the Hold back. */
@ApiIntegrationTest
class HoldApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	@Autowired
	TestClock clock;

	HoldApi holds;

	@BeforeEach
	void setUp() {
		this.clock.reset();
		this.holds = new HoldApi(this.client, this.jwts);
	}

	@Test
	void aCustomerHoldsSeatsAndGeneralAdmissionPlacesAtTheSectionPrices() {
		SellableShow show = this.holds.publishedShow();
		Instant before = Instant.now().minusSeconds(1);

		RestTestClient.ResponseSpec response = this.holds.hold(show.id(), this.jwts.customer().encode(),
				body(List.of(show.seats().get(2), show.seats().get(0)), places(show.standing(), 3)));

		JsonNode hold = HoldApi.read(response.expectStatus().isCreated());
		assertThat(hold.path("id").asString()).isNotBlank();
		assertThat(hold.path("showId").asString()).isEqualTo(show.id());
		assertThat(hold.path("status").asString()).isEqualTo("ACTIVE");
		Instant createdAt = Instant.parse(hold.path("createdAt").asString());
		assertThat(createdAt).isAfter(before).isBefore(Instant.now().plusSeconds(1));
		assertThat(Instant.parse(hold.path("expiresAt").asString())).isEqualTo(createdAt.plus(Duration.ofMinutes(10)));
		assertThat(hold.path("totalPaise").asLong())
			.isEqualTo(2 * HoldApi.STALLS_PAISE + 3 * HoldApi.STANDING_PAISE);
		assertThat(hold.path("currency").asString()).isEqualTo("INR");
		JsonNode items = hold.path("items");
		assertThat(items).hasSize(3);
		assertSeat(items.get(0), show, 0, "A", 1);
		assertSeat(items.get(1), show, 2, "B", 1);
		JsonNode standing = items.get(2);
		assertThat(standing.path("kind").asString()).isEqualTo("GENERAL_ADMISSION");
		assertThat(standing.path("sectionId").asString()).isEqualTo(show.standing());
		assertThat(standing.path("quantity").asInt()).isEqualTo(3);
		assertThat(standing.path("pricePaise").asLong()).isEqualTo(HoldApi.STANDING_PAISE);
		assertThat(standing.path("seatId").isNull()).isTrue();
		response.expectHeader().location("/api/v1/holds/" + hold.path("id").asString());
	}

	@Test
	void theCustomerReadsTheirHoldBack() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		JsonNode created = this.holds.held(show.id(), customer, body(List.of(show.seats().get(1)), places(show.standing(), 2)));

		JsonNode read = HoldApi.read(this.holds.get(created.path("id").asString(), customer).expectStatus().isOk());

		assertThat(read).isEqualTo(created);
	}

	@Test
	void anyoneElsesHoldIsNotFound() {
		SellableShow show = this.holds.publishedShow();
		String hold = this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(0))))
			.path("id")
			.asString();

		assertHoldNotFound(this.holds.get(hold, this.jwts.customer().encode()));
		assertHoldNotFound(this.holds.get(hold, this.jwts.organizer().encode()));
		assertHoldNotFound(this.holds.get("00000000-0000-0000-0000-000000000000", this.jwts.customer().encode()));
		this.holds.get(hold, null).expectStatus().isUnauthorized();
	}

	@Test
	void onlyASignedInCustomerCanHold() {
		SellableShow show = this.holds.publishedShow();
		String body = body(List.of(show.seats().get(0)));

		this.holds.hold(show.id(), null, body).expectStatus().isUnauthorized();
		this.holds.hold(show.id(), this.jwts.as("ORGANIZER").encode(), body)
			.expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:forbidden");
		assertThat(seat(this.holds.availability(show.id()), 0)).isTrue();
	}

	@Test
	void heldSeatsAreUnavailableAndHeldPlacesAreTakenOffTheCount() {
		SellableShow show = this.holds.publishedShow();

		this.holds.held(show.id(), this.jwts.customer().encode(),
				body(List.of(show.seats().get(0), show.seats().get(3)), places(show.standing(), 4)));

		JsonNode availability = this.holds.availability(show.id());
		assertThat(List.of(seat(availability, 0), seat(availability, 1), seat(availability, 2), seat(availability, 3)))
			.containsExactly(false, true, true, false);
		assertThat(availability.at("/sections/1/available").asInt()).isEqualTo(496);
		assertThat(availability.at("/sections/1/capacity").asInt()).isEqualTo(500);
	}

	@Test
	void whenAnythingIsGoneNothingIsHeldAndThe409SaysWhat() {
		SellableShow show = this.holds.publishedShow();
		this.holds.held(show.id(), this.jwts.customer().encode(),
				body(List.of(show.seats().get(0), show.seats().get(2)), places(show.standing(), 8)));
		JsonNode before = this.holds.availability(show.id());

		RestTestClient.ResponseSpec response = this.holds.hold(show.id(), this.jwts.customer().encode(),
				body(List.of(show.seats().get(1), show.seats().get(2), show.seats().get(0)),
						places(show.standing(), 5)));

		JsonNode problem = HoldApi.read(response.expectStatus().isEqualTo(409));
		assertThat(problem.path("type").asString()).isEqualTo("urn:getmyseat:problem:inventory-unavailable");
		assertThat(problem.path("unavailableSeats").valueStream().map(JsonNode::asString))
			.containsExactly(show.seats().get(2), show.seats().get(0));
		assertThat(problem.path("unavailableSections")).isEmpty();
		assertThat(this.holds.availability(show.id())).isEqualTo(before);
	}

	@Test
	void aShortGeneralAdmissionSectionAloneFailsTheWholeHold() {
		SellableShow show = this.holds.publishedShow();
		for (int i = 0; i < 49; i++) {
			this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(), places(show.standing(), 10)));
		}
		this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(), places(show.standing(), 8)));

		JsonNode problem = HoldApi.read(this.holds
			.hold(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(1)), places(show.standing(), 3)))
			.expectStatus()
			.isEqualTo(409));

		assertThat(problem.path("unavailableSeats")).isEmpty();
		assertThat(problem.at("/unavailableSections/0/available").asInt()).isEqualTo(2);
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seat(availability, 1)).isTrue();
		assertThat(availability.at("/sections/1/available").asInt()).isEqualTo(2);
		this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(1)), places(show.standing(), 2)));
		assertThat(this.holds.availability(show.id()).at("/sections/1/available").asInt()).isZero();
	}

	@Test
	void overlappingHoldsRacingInOppositeOrdersGetOneWinnerAndConflictsNotErrors() throws Exception {
		SellableShow show = this.holds.publishedShow();
		List<String> forwards = show.seats();
		List<String> backwards = show.seats().reversed();
		int customers = 16;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RestTestClient.ResponseSpec>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(customers)) {
			for (int i = 0; i < customers; i++) {
				String token = this.jwts.customer().encode();
				String body = body((i % 2 == 0) ? forwards : backwards, places(show.standing(), 1));
				responses.add(pool.submit(() -> {
					start.await();
					return this.holds.hold(show.id(), token, body);
				}));
			}
			start.countDown();
			int held = 0;
			for (Future<RestTestClient.ResponseSpec> response : responses) {
				JsonNode result = HoldApi.read(response.get());
				if (result.has("status") && "ACTIVE".equals(result.path("status").asString())) {
					held++;
				}
				else {
					assertThat(result.path("type").asString()).isEqualTo("urn:getmyseat:problem:inventory-unavailable");
				}
			}
			assertThat(held).isEqualTo(1);
		}
		JsonNode availability = this.holds.availability(show.id());
		assertThat(availability.at("/sections/1/available").asInt()).isEqualTo(499);
	}

	@Test
	void aHoldIsOneToTenTickets() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();

		assertInvalid(this.holds.hold(show.id(), customer, "{}"), "tickets");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of())), "tickets");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(show.seats().get(0)), places(show.standing(), 10))),
				"tickets");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(), places(show.standing(), 11))), "tickets");
		this.holds.held(show.id(), customer, body(List.of(show.seats().get(0)), places(show.standing(), 9)));
	}

	@Test
	void everyItemMustBeAWellFormedPartOfTheShowsVenue() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String seat = show.seats().get(0);
		String elsewhere = UUID.randomUUID().toString();

		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(), places(show.standing(), 0))),
				"generalAdmission[0].quantity");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(), places(show.standing(), -2))),
				"generalAdmission[0].quantity");
		assertInvalid(this.holds.hold(show.id(), customer, "{ \"generalAdmission\": [ { \"quantity\": 1 } ] }"),
				"generalAdmission[0].sectionId");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(seat, show.seats().get(1), seat))), "seats[2]");
		assertInvalid(this.holds.hold(show.id(), customer,
				body(List.of(), places(show.standing(), 1), places(show.standing(), 2))),
				"generalAdmission[1].sectionId");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(seat, elsewhere))), "seats[1]");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(), places(elsewhere, 1))),
				"generalAdmission[0].sectionId");
		assertInvalid(this.holds.hold(show.id(), customer, "{ \"seats\": [ \"not-a-seat\" ] }"), null);
		assertThat(this.holds.availability(show.id()).at("/sections/1/available").asInt()).isEqualTo(500);
	}

	@Test
	void seatsAndGeneralAdmissionPlacesAreAskedForThroughTheirOwnKindOfItem() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();

		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(show.standing()))), "seats[0]");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(show.stalls()))), "seats[0]");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(), places(show.stalls(), 2))),
				"generalAdmission[0].sectionId");
		assertInvalid(this.holds.hold(show.id(), customer, body(List.of(), places(show.seats().get(0), 1))),
				"generalAdmission[0].sectionId");
	}

	@Test
	void aDraftOrUnknownShowIsNotFound() {
		String organizer = this.jwts.organizer().encode();
		String venue = this.holds.shows.approvedVenue();
		String draft = this.holds.shows.priced(
				this.holds.shows.draftShow(this.holds.shows.publishedEvent(organizer), organizer, venue), organizer,
				venue);
		String section = this.holds.shows.sections(venue).get(1);

		assertShowNotFound(this.holds.hold(draft, this.jwts.customer().encode(), body(List.of(), places(section, 1))));
		assertShowNotFound(this.holds.hold("00000000-0000-0000-0000-000000000000", this.jwts.customer().encode(),
				body(List.of(), places(section, 1))));
	}

	@Test
	void aShowThatHasStartedCantBeHeld() {
		SellableShow show = this.holds.publishedShow();
		this.clock.advance(Duration.ofDays(8));

		this.holds.hold(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(0))))
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict")
			.jsonPath("$.detail")
			.isEqualTo("The Show has already started.");
		assertThat(seat(this.holds.availability(show.id()), 0)).isTrue();
	}

	private static boolean seat(JsonNode availability, int seat) {
		return availability.at("/sections/0/seats/" + seat + "/available").asBoolean();
	}

	private static void assertInvalid(RestTestClient.ResponseSpec response, @Nullable String field) {
		RestTestClient.BodyContentSpec body = response.expectStatus().isBadRequest().expectBody();
		if (field != null) {
			body.jsonPath("$.type").isEqualTo("urn:getmyseat:problem:validation").jsonPath("$.errors[0].field").isEqualTo(field);
		}
	}

	private static void assertHoldNotFound(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isNotFound()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:not-found")
			.jsonPath("$.detail")
			.isEqualTo("No Hold with that id.");
	}

	private static void assertShowNotFound(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isNotFound()
			.expectBody()
			.jsonPath("$.detail")
			.isEqualTo("No Show with that id.");
	}

	private static void assertSeat(JsonNode item, SellableShow show, int seat, String row, int number) {
		assertThat(item.path("kind").asString()).isEqualTo("SEAT");
		assertThat(item.path("sectionId").asString()).isEqualTo(show.stalls());
		assertThat(item.path("seatId").asString()).isEqualTo(show.seats().get(seat));
		assertThat(item.path("rowLabel").asString()).isEqualTo(row);
		assertThat(item.path("seatNumber").asInt()).isEqualTo(number);
		assertThat(item.path("quantity").asInt()).isEqualTo(1);
		assertThat(item.path("pricePaise").asLong()).isEqualTo(HoldApi.STALLS_PAISE);
	}

}
