package com.getmyseat.booking;

import static com.getmyseat.booking.HoldApi.body;
import static com.getmyseat.booking.HoldApi.places;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

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
 * A Hold that is looked at after its expiry time expires right then and gives back its inventory. The cleanup job
 * doesn't run in these tests, so what they see is the lazy expiry alone.
 */
@ApiIntegrationTest
class HoldExpiryApiIT {

	/** The default Hold time, 10 minutes, and a moment. */
	private static final Duration PAST_EXPIRY = Duration.ofMinutes(10).plusSeconds(1);

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
	void aHoldReadAfterItsExpiryTimeIsExpiredAndGivesBackItsInventory() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0), show.seats().get(1)), places(show.standing(), 4))));

		this.clock.advance(PAST_EXPIRY);

		assertThat(status(this.holds.get(hold, customer))).isEqualTo("EXPIRED");
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsOnly(true);
		assertThat(standing(availability)).isEqualTo(500);
		assertMineNotFound(this.holds.mine(show.id(), customer));
	}

	@Test
	void mineAfterTheExpiryTimeExpiresTheHoldAndIsNotFound() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(2)), places(show.standing(), 3))));

		this.clock.advance(PAST_EXPIRY);

		assertMineNotFound(this.holds.mine(show.id(), customer));
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsOnly(true);
		assertThat(standing(availability)).isEqualTo(500);
		assertThat(status(this.holds.get(hold, customer))).isEqualTo("EXPIRED");
	}

	@Test
	void aNewHoldAfterTheExpiryTimeExpiresTheOldOneRatherThanReleasingIt() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String old = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0), show.seats().get(1)), places(show.standing(), 4))));
		this.clock.advance(PAST_EXPIRY);

		JsonNode replacement = this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(1)), places(show.standing(), 2)));

		assertThat(status(this.holds.get(old, customer))).isEqualTo("EXPIRED");
		assertThat(replacement.path("status").asString()).isEqualTo("ACTIVE");
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsExactly(true, false, true, true);
		assertThat(standing(availability)).isEqualTo(498);
	}

	@Test
	void releasingAHoldPastItsExpiryTimeIsAConflict() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0)), places(show.standing(), 4))));
		this.clock.advance(PAST_EXPIRY);

		assertExpired(this.holds.release(hold, customer));

		assertThat(status(this.holds.get(hold, customer))).isEqualTo("EXPIRED");
		assertExpired(this.holds.release(hold, customer));
		JsonNode availability = this.holds.availability(show.id());
		assertThat(seats(availability)).containsOnly(true);
		assertThat(standing(availability)).isEqualTo(500);
	}

	@Test
	void availabilityReadsDontExpireHolds() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0)), places(show.standing(), 4))));
		this.clock.advance(PAST_EXPIRY);

		JsonNode availability = this.holds.availability(show.id());

		assertThat(seats(availability)).containsExactly(false, true, true, true);
		assertThat(standing(availability)).isEqualTo(496);
		assertThat(status(this.holds.get(hold, customer))).isEqualTo("EXPIRED");
	}

	private static String id(JsonNode hold) {
		return hold.path("id").asString();
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

	private static void assertExpired(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict")
			.jsonPath("$.detail")
			.isEqualTo("The Hold has expired.");
	}

	private static void assertMineNotFound(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isNotFound()
			.expectBody()
			.jsonPath("$.detail")
			.isEqualTo("You have no active Hold for that Show.");
	}

}
