package com.getmyseat.booking;

import static com.getmyseat.booking.HoldApi.body;
import static com.getmyseat.booking.HoldApi.places;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/**
 * Every Hold creation carries an {@code Idempotency-Key}, so a client retrying after a network failure gets the
 * original Hold back and never claims inventory twice.
 */
@ApiIntegrationTest
class HoldIdempotencyApiIT {

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
	void holdingNeedsAnIdempotencyKeyOfOneTo255Characters() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String body = body(List.of(show.seats().get(0)), places(show.standing(), 2));

		assertInvalidKey(this.holds.hold(show.id(), customer, body, null));
		assertInvalidKey(this.holds.hold(show.id(), customer, body, ""));
		assertInvalidKey(this.holds.hold(show.id(), customer, body, "k".repeat(256)));
		assertThat(this.holds.availability(show.id()).at("/sections/1/available").asInt()).isEqualTo(500);
		this.holds.hold(show.id(), customer, body, "k".repeat(255)).expectStatus().isCreated();
		this.holds.hold(show.id(), this.jwts.customer().encode(), body(List.of(), places(show.standing(), 1)), "k")
			.expectStatus()
			.isCreated();
	}

	@Test
	void aRetryOfTheSameRequestGetsTheOriginalHoldBackAndClaimsNothing() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String key = UUID.randomUUID().toString();
		JsonNode original = HoldApi.read(this.holds
			.hold(show.id(), customer, body(List.of(show.seats().get(0), show.seats().get(2)), places(show.standing(), 3)),
					key)
			.expectStatus()
			.isCreated());
		JsonNode before = this.holds.availability(show.id());

		// The same request, with its items in another order.
		RestTestClient.ResponseSpec retry = this.holds.hold(show.id(), customer,
				"{ \"generalAdmission\": [ %s ], \"seats\": [ \"%s\", \"%s\" ] }".formatted(places(show.standing(), 3),
						show.seats().get(2), show.seats().get(0)),
				key);

		assertThat(HoldApi.read(retry.expectStatus().isCreated())).isEqualTo(original);
		retry.expectHeader().location("/api/v1/holds/" + original.path("id").asString());
		assertThat(this.holds.availability(show.id())).isEqualTo(before);
		assertThat(HoldApi.read(this.holds.get(original.path("id").asString(), customer).expectStatus().isOk()))
			.isEqualTo(original);
	}

	@Test
	void aRetryGetsTheOriginalHoldInItsCurrentState() {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String body = body(List.of(show.seats().get(1)));
		String released = HoldApi.read(this.holds.hold(show.id(), customer, body, "released").expectStatus().isCreated())
			.path("id")
			.asString();
		this.holds.release(released, customer).expectStatus().isOk();
		String expired = HoldApi.read(this.holds.hold(show.id(), customer, body, "expired").expectStatus().isCreated())
			.path("id")
			.asString();
		this.clock.advance(Duration.ofMinutes(11));

		JsonNode replayedRelease = HoldApi
			.read(this.holds.hold(show.id(), customer, body, "released").expectStatus().isCreated());
		JsonNode replayedExpiry = HoldApi
			.read(this.holds.hold(show.id(), customer, body, "expired").expectStatus().isCreated());

		assertThat(replayedRelease.path("id").asString()).isEqualTo(released);
		assertThat(replayedRelease.path("status").asString()).isEqualTo("RELEASED");
		assertThat(replayedExpiry.path("id").asString()).isEqualTo(expired);
		assertThat(replayedExpiry.path("status").asString()).isEqualTo("EXPIRED");
		assertThat(this.holds.availability(show.id()).at("/sections/0/seats/1/available").asBoolean()).isTrue();
		this.holds.mine(show.id(), customer).expectStatus().isNotFound();
	}

	@Test
	void theSameKeyWithADifferentRequestIsAConflictAndHoldsNothing() {
		SellableShow show = this.holds.publishedShow();
		SellableShow other = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String hold = HoldApi
			.read(this.holds.hold(show.id(), customer, body(List.of(show.seats().get(0)), places(show.standing(), 2)), "key")
				.expectStatus()
				.isCreated())
			.path("id")
			.asString();
		JsonNode before = this.holds.availability(show.id());

		assertKeyReused(this.holds.hold(show.id(), customer, body(List.of(show.seats().get(0)), places(show.standing(), 3)), "key"));
		assertKeyReused(this.holds.hold(show.id(), customer, body(List.of(show.seats().get(1)), places(show.standing(), 2)), "key"));
		assertKeyReused(this.holds.hold(show.id(), customer, body(List.of(show.seats().get(0))), "key"));
		assertKeyReused(this.holds.hold(other.id(), customer, body(List.of(other.seats().get(0)), places(other.standing(), 2)), "key"));

		assertThat(this.holds.availability(show.id())).isEqualTo(before);
		assertThat(this.holds.availability(other.id()).at("/sections/1/available").asInt()).isEqualTo(500);
		assertThat(HoldApi.read(this.holds.mine(show.id(), customer).expectStatus().isOk()).path("id").asString())
			.isEqualTo(hold);
	}

	@Test
	void keysAreScopedPerCustomer() {
		SellableShow show = this.holds.publishedShow();
		String body = body(List.of(), places(show.standing(), 2));

		String first = HoldApi.read(this.holds.hold(show.id(), this.jwts.customer().encode(), body, "shared").expectStatus().isCreated())
			.path("id")
			.asString();
		String second = HoldApi.read(this.holds.hold(show.id(), this.jwts.customer().encode(), body, "shared").expectStatus().isCreated())
			.path("id")
			.asString();

		assertThat(second).isNotEqualTo(first);
		assertThat(this.holds.availability(show.id()).at("/sections/1/available").asInt()).isEqualTo(496);
	}

	@Test
	void aRequestThatFailsLeavesNoKeySoARetryTriesAgain() {
		SellableShow show = this.holds.publishedShow();
		String rival = this.jwts.customer().encode();
		String blocking = HoldApi.read(this.holds.hold(show.id(), rival, body(List.of(show.seats().get(0))), "rival")
			.expectStatus()
			.isCreated()).path("id").asString();
		String customer = this.jwts.customer().encode();
		String body = body(List.of(show.seats().get(0)), places(show.standing(), 1));

		this.holds.hold(show.id(), customer, body, "retry")
			.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:inventory-unavailable");
		this.holds.release(blocking, rival).expectStatus().isOk();
		JsonNode retried = HoldApi.read(this.holds.hold(show.id(), customer, body, "retry").expectStatus().isCreated());

		assertThat(retried.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(this.holds.availability(show.id()).at("/sections/1/available").asInt()).isEqualTo(499);
	}

	@Test
	void concurrentRequestsWithTheSameKeyMakeOneHoldAndAllGetItBack() throws Exception {
		SellableShow show = this.holds.publishedShow();
		String customer = this.jwts.customer().encode();
		String body = body(List.of(show.seats().get(3)), places(show.standing(), 2));
		String key = UUID.randomUUID().toString();
		int requests = 12;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<RestTestClient.ResponseSpec>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				responses.add(pool.submit(() -> {
					start.await();
					return this.holds.hold(show.id(), customer, body, key);
				}));
			}
			start.countDown();
			Set<String> ids = new HashSet<>();
			for (Future<RestTestClient.ResponseSpec> response : responses) {
				JsonNode hold = HoldApi.read(response.get().expectStatus().isCreated());
				assertThat(hold.path("status").asString()).isEqualTo("ACTIVE");
				ids.add(hold.path("id").asString());
			}
			assertThat(ids).hasSize(1);
		}
		JsonNode availability = this.holds.availability(show.id());
		assertThat(availability.at("/sections/0/seats/3/available").asBoolean()).isFalse();
		assertThat(availability.at("/sections/1/available").asInt()).isEqualTo(498);
	}

	private static void assertKeyReused(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:idempotency-key-reused");
	}

	private static void assertInvalidKey(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[0].field")
			.isEqualTo("Idempotency-Key");
	}

}
