package com.getmyseat.booking;

import static com.getmyseat.booking.HoldApi.body;
import static com.getmyseat.booking.HoldApi.places;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.booking.HoldApi.SellableShow;
import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestClock;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/**
 * A Customer has at most one active Hold per Show: a new Hold replaces the old one, all or nothing. They can release
 * a Hold early and find their active Hold again after a reload.
 */
@ApiIntegrationTest
class HoldReleaseApiIT {

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
	void aNewHoldForTheSameShowReleasesTheOldOneAndCanRetakeItsSeats() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String old = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0), show.seats().get(1)), places(show.standing(), 4))));

		JsonNode replacement = this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(1), show.seats().get(2)), places(show.standing(), 2)));

		assertThat(status(this.holds.get(old, customer))).isEqualTo("RELEASED");
		assertThat(replacement.path("status").asString()).isEqualTo("ACTIVE");
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsExactly(true, false, false, true);
		assertThat(standing(availability)).isEqualTo(498);
		assertThat(HoldApi.read(this.holds.mine(show.id(), customer).expectStatus().isOk())).isEqualTo(replacement);
	}

	@Test
	void whenTheNewHoldIsUnavailableTheOldOneStaysActiveWithItsInventory() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String old = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0)), places(show.standing(), 3))));
		this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(3))));
		JsonNode before = this.holds.availability(show.id());

		this.holds.hold(show.id(), customer, body(List.of(show.seats().get(0), show.seats().get(3))))
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:inventory-unavailable");

		assertThat(status(this.holds.get(old, customer))).isEqualTo("ACTIVE");
		assertThat(this.holds.availability(show.id())).isEqualTo(before);
		assertThat(mine(show.id(), customer)).isEqualTo(old);
	}

	@Test
	void whenTheNewHoldIsInvalidTheOldOneStaysActiveWithItsInventory() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String old = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0)), places(show.standing(), 3))));
		JsonNode before = this.holds.availability(show.id());

		this.holds.hold(show.id(), customer, body(List.of(), places(show.standing(), 11)))
			.expectStatus()
			.isBadRequest();

		assertThat(status(this.holds.get(old, customer))).isEqualTo("ACTIVE");
		assertThat(this.holds.availability(show.id())).isEqualTo(before);
	}

	@Test
	void holdsForDifferentShowsDontAffectEachOther() {
		SellableShow first = this.holds.publishedShow();
		SellableShow second = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String firstHold = id(this.holds.held(first.id(), customer,
				body(List.of(first.seats().get(0)), places(first.standing(), 2))));

		String secondHold = id(this.holds.held(second.id(), customer, body(List.of(second.seats().get(0)))));

		assertThat(status(this.holds.get(firstHold, customer))).isEqualTo("ACTIVE");
		assertThat(status(this.holds.get(secondHold, customer))).isEqualTo("ACTIVE");
		assertThat(seats(this.holds.availability(first.id()))).containsExactly(false, true, true, true);
		assertThat(standing(this.holds.availability(first.id()))).isEqualTo(498);
		assertThat(mine(first.id(), customer)).isEqualTo(firstHold);
		assertThat(mine(second.id(), customer)).isEqualTo(secondHold);
	}

	@Test
	void otherCustomersHoldsForTheSameShowAreUntouched() {
		SellableShow show = this.holds.publishedShow();
		String someoneElse = this.jwts.customer().encode();
		String theirs = id(this.holds.held(show.id(), someoneElse, body(List.of(show.seats().get(0)))));

		this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(1))));

		assertThat(status(this.holds.get(theirs, someoneElse))).isEqualTo("ACTIVE");
		assertThat(seats(this.holds.availability(show.id()))).containsExactly(false, false, true, true);
	}

	@Test
	void aCustomerReleasesTheirHoldAndItsInventoryComesBack() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0), show.seats().get(2)), places(show.standing(), 5))));

		JsonNode released = HoldApi.read(this.holds.release(hold, customer).expectStatus().isOk());

		assertThat(released.path("id").asString()).isEqualTo(hold);
		assertThat(released.path("status").asString()).isEqualTo("RELEASED");
		assertThat(status(this.holds.get(hold, customer))).isEqualTo("RELEASED");
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsOnly(true);
		assertThat(standing(availability)).isEqualTo(500);
		assertMineNotFound(this.holds.mine(show.id(), customer));
	}

	@Test
	void releasingAHoldThatIsntActiveIsAConflictAndGivesNothingBackTwice() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer, body(List.of(), places(show.standing(), 5))));
		this.holds.release(hold, customer).expectStatus().isOk();
		String replaced = id(this.holds.held(show.id(), customer, body(List.of(), places(show.standing(), 2))));
		this.holds.held(show.id(), customer, body(List.of(), places(show.standing(), 3)));

		assertConflict(this.holds.release(hold, customer));
		assertConflict(this.holds.release(replaced, customer));

		assertThat(standing(this.holds.availability(show.id()))).isEqualTo(497);
	}

	@Test
	void releasesRacingOnTheSameHoldGiveItsPlacesBackOnce() throws Exception {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0)), places(show.standing(), 6))));
		int requests = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RestTestClient.ResponseSpec>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				responses.add(pool.submit(() -> {
					start.await();
					return this.holds.release(hold, customer);
				}));
			}
			start.countDown();
			int released = 0;
			for (Future<RestTestClient.ResponseSpec> response : responses) {
				JsonNode result = HoldApi.read(response.get());
				if ("RELEASED".equals(result.path("status").asString())) {
					released++;
				}
				else {
					assertThat(result.path("status").asInt()).isEqualTo(409);
				}
			}
			assertThat(released).isEqualTo(1);
		}

		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsOnly(true);
		assertThat(standing(availability)).isEqualTo(500);
	}

	@Test
	void onlyTheOwnerCanReleaseAHold() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer, body(List.of(show.seats().get(0)))));

		assertHoldNotFound(this.holds.release(hold, this.jwts.customer().encode()));
		assertHoldNotFound(this.holds.release(hold, this.jwts.organizer().encode()));
		assertHoldNotFound(this.holds.release("00000000-0000-0000-0000-000000000000", customer));
		this.holds.release(hold, null).expectStatus().isUnauthorized();

		assertThat(status(this.holds.get(hold, customer))).isEqualTo("ACTIVE");
		assertThat(seats(this.holds.availability(show.id()))).containsExactly(false, true, true, true);
	}

	@Test
	void mineIsNotFoundWithoutAnActiveHoldForTheShow() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		this.holds.held(show.id(), this.jwts.customer().encode(), body(List.of(show.seats().get(0))));

		assertMineNotFound(this.holds.mine(show.id(), customer));
		assertMineNotFound(this.holds.mine("00000000-0000-0000-0000-000000000000", customer));
		this.holds.mine(show.id(), null).expectStatus().isUnauthorized();
	}

	@Test
	void aCustomerRacingThemselvesEndsUpWithOneActiveHoldAndNoLostInventory() throws Exception {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		this.holds.held(show.id(), customer, body(List.of(show.seats().get(0)), places(show.standing(), 4)));
		int requests = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RestTestClient.ResponseSpec>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				String body = body(List.of(show.seats().get(i % 4)), places(show.standing(), 1 + (i % 3)));
				responses.add(pool.submit(() -> {
					start.await();
					return this.holds.hold(show.id(), customer, body);
				}));
			}
			start.countDown();
			for (Future<RestTestClient.ResponseSpec> response : responses) {
				JsonNode result = HoldApi.read(response.get());
				if (!result.path("status").isString()) {
					assertThat(result.path("type").asString()).isIn("urn:getmyseat:problem:inventory-unavailable",
							"urn:getmyseat:problem:conflict");
				}
			}
		}

		JsonNode mine = HoldApi.read(this.holds.mine(show.id(), customer).expectStatus().isOk());
		JsonNode availability = this.holds.availability(show.id());
		String heldSeat = mine.at("/items/0/seatId").asString();
		for (int seat = 0; seat < 4; seat++) {
			assertThat(availability.at("/sections/0/seats/" + seat + "/available").asBoolean())
				.isEqualTo(!show.seats().get(seat).equals(heldSeat));
		}
		assertThat(standing(availability)).isEqualTo(500 - mine.at("/items/1/quantity").asInt());
	}

	private static String id(JsonNode hold) {
		return hold.path("id").asString();
	}

	/** The id of the Customer's active Hold for the Show. */
	private String mine(String show, String customer) {
		return id(HoldApi.read(this.holds.mine(show, customer).expectStatus().isOk()));
	}

	/** General Admission places left in the Standing Section. */
	private static int standing(JsonNode availability) {
		return availability.at("/sections/1/available").asInt();
	}

	private static String status(RestTestClient.ResponseSpec response) {
		return HoldApi.read(response.expectStatus().isOk()).path("status").asString();
	}

	private static List<Boolean> seats(JsonNode availability) {
		return availability.at("/sections/0/seats")
			.valueStream()
			.map(seat -> seat.path("available").asBoolean())
			.toList();
	}

	private static void assertConflict(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict")
			.jsonPath("$.detail")
			.isEqualTo("The Hold isn't active.");
	}

	private static void assertHoldNotFound(RestTestClient.ResponseSpec response) {
		response.expectStatus().isNotFound().expectBody().jsonPath("$.detail").isEqualTo("No Hold with that id.");
	}

	private static void assertMineNotFound(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isNotFound()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:not-found")
			.jsonPath("$.detail")
			.isEqualTo("You have no active Hold for that Show.");
	}

}
