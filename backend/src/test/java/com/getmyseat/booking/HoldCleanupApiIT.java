package com.getmyseat.booking;

import static com.getmyseat.booking.HoldApi.body;
import static com.getmyseat.booking.HoldApi.places;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.booking.HoldApi.SellableShow;
import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestClock;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/**
 * The cleanup job expires Holds that nobody reads, and forgets day-old Idempotency-Keys, here every 100 milliseconds.
 */
@ApiIntegrationTest
@TestPropertySource(properties = "getmyseat.holds.cleanup-interval=100ms")
class HoldCleanupApiIT {

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
	void theCleanupJobExpiresHoldsNobodyReadsAndGivesBackTheirInventory() throws InterruptedException {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String first = id(this.holds.held(show.id(), customer,
				body(List.of(show.seats().get(0), show.seats().get(1)), places(show.standing(), 4))));
		this.holds.held(show.id(), this.jwts.customer().encode(),
				body(List.of(show.seats().get(2)), places(show.standing(), 3)));
		String fresh = this.jwts.customer().encode();
		this.clock.advance(PAST_EXPIRY);
		String unexpired = id(this.holds.held(show.id(), fresh, body(List.of(show.seats().get(3)))));

		JsonNode availability = awaitAvailability(show.id(), a -> standing(a) == 500);

		assertThat(seats(availability)).containsExactly(true, true, true, false);
		assertThat(status(this.holds.get(first, customer))).isEqualTo("EXPIRED");
		assertThat(status(this.holds.get(unexpired, fresh))).isEqualTo("ACTIVE");
	}

	@Test
	void theCleanupJobLazyReadsAndReleasesRacingOnAnExpiredHoldGiveItsPlacesBackOnce() throws Exception {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = id(this.holds.held(show.id(), customer, body(List.of(), places(show.standing(), 7))));
		this.clock.advance(PAST_EXPIRY);
		int requests = 12;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RestTestClient.ResponseSpec>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				int kind = i % 3;
				// Spread over a few cleanup runs, so some requests meet the job mid-batch.
				long delayMillis = 25L * i;
				responses.add(pool.submit(() -> {
					start.await();
					Thread.sleep(delayMillis);
					return switch (kind) {
						case 0 -> this.holds.get(hold, customer);
						case 1 -> this.holds.mine(show.id(), customer);
						default -> this.holds.release(hold, customer);
					};
				}));
			}
			start.countDown();
			for (int i = 0; i < requests; i++) {
				RestTestClient.ResponseSpec response = responses.get(i).get();
				switch (i % 3) {
					case 0 -> assertThat(status(response)).isEqualTo("EXPIRED");
					case 1 -> response.expectStatus().isNotFound();
					default -> response.expectStatus().isEqualTo(409);
				}
			}
		}

		assertThat(standing(this.holds.availability(show.id()))).isEqualTo(500);
		// A few more cleanup runs mustn't give anything back either.
		Thread.sleep(300);
		assertThat(standing(this.holds.availability(show.id()))).isEqualTo(500);
	}

	@Test
	void theCleanupJobForgetsIdempotencyKeysAfter24Hours() throws InterruptedException {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		this.holds.hold(show.id(), customer, body(List.of(show.seats().get(0))), "day-old").expectStatus().isCreated();
		String differentRequest = body(List.of(show.seats().get(1)));
		this.clock.advance(Duration.ofHours(23));
		Thread.sleep(300);
		this.holds.hold(show.id(), customer, differentRequest, "day-old").expectStatus().isEqualTo(409);

		this.clock.advance(Duration.ofHours(1).plusSeconds(1));

		Instant deadline = Instant.now().plusSeconds(10);
		while (this.holds.hold(show.id(), customer, differentRequest, "day-old")
			.returnResult()
			.getStatus()
			.value() != 201) {
			assertThat(Instant.now()).as("the key to be forgotten").isBefore(deadline);
			Thread.sleep(50);
		}
	}

	/** Polls the Show's availability until it matches, for up to 10 seconds. */
	private JsonNode awaitAvailability(String show, Predicate<JsonNode> condition) throws InterruptedException {
		Instant deadline = Instant.now().plusSeconds(10);
		JsonNode availability = this.holds.availability(show);
		while (!condition.test(availability)) {
			assertThat(Instant.now()).as("availability to come back").isBefore(deadline);
			Thread.sleep(50);
			availability = this.holds.availability(show);
		}
		return availability;
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

}
