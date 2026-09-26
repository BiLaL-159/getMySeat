package com.getmyseat.booking;

import static com.getmyseat.booking.HoldApi.body;
import static com.getmyseat.booking.HoldApi.places;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.booking.HoldApi.SellableShow;
import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestClock;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inventory is never sold twice under contention: 500 Customers asking for the same inventory at the same moment get
 * exactly as many Holds as there is inventory, and everyone else a {@code 409}, never a {@code 5xx}.
 */
@ApiIntegrationTest
class HoldContentionApiIT {

	private static final int CUSTOMERS = 500;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private record Result(int status, JsonNode body) {
	}

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
	void fiveHundredCustomersRacingForOneSeatGetOneHold() throws Exception {
		SellableShow show = this.holds.publishedShow();
		String seat = show.seats().get(0);

		List<Result> results = race(show, body(List.of(seat)));

		assertThat(statuses(results)).containsExactly(Map.entry(201, 1), Map.entry(409, CUSTOMERS - 1));
		assertLosersFoundTheInventoryGone(results);
		assertThat(this.holds.availability(show.id()).at("/sections/0/seats/0/available").asBoolean()).isFalse();
	}

	@Test
	void fiveHundredCustomersRacingForAHundredPlacesGetAHundredHolds() throws Exception {
		SellableShow show = this.holds.publishedShow(100);

		List<Result> results = race(show, body(List.of(), places(show.standing(), 1)));

		assertThat(statuses(results)).containsExactly(Map.entry(201, 100), Map.entry(409, CUSTOMERS - 100));
		assertLosersFoundTheInventoryGone(results);
		assertThat(this.holds.availability(show.id()).at("/sections/1/available").asInt()).isZero();
	}

	/**
	 * {@link #CUSTOMERS} Customers, each with their own token and {@code Idempotency-Key}, send the same Hold request
	 * at once: every thread is started and waiting before any is let go.
	 */
	private List<Result> race(SellableShow show, String body) throws Exception {
		List<String> customers = new ArrayList<>();
		for (int i = 0; i < CUSTOMERS; i++) {
			customers.add(this.jwts.customer().encode());
		}
		CountDownLatch ready = new CountDownLatch(CUSTOMERS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Result>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(CUSTOMERS)) {
			for (String customer : customers) {
				responses.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return result(this.holds.hold(show.id(), customer, body));
				}));
			}
			ready.await();
			start.countDown();
			List<Result> results = new ArrayList<>();
			for (Future<Result> response : responses) {
				results.add(response.get());
			}
			return results;
		}
	}

	private static Result result(RestTestClient.ResponseSpec response) {
		EntityExchangeResult<byte[]> result = response.expectBody().returnResult();
		byte[] body = result.getResponseBody();
		return new Result(result.getStatus().value(), (body != null) ? JSON.readTree(body) : JSON.missingNode());
	}

	/** How many responses had each status, in status order, so a {@code 5xx} shows up as an entry of its own. */
	private static Map<Integer, Integer> statuses(List<Result> results) {
		Map<Integer, Integer> counts = new TreeMap<>();
		results.forEach(result -> counts.merge(result.status(), 1, Integer::sum));
		return counts;
	}

	private static void assertLosersFoundTheInventoryGone(List<Result> results) {
		assertThat(results).filteredOn(result -> result.status() == 409)
			.extracting(result -> result.body().path("type").asString())
			.containsOnly("urn:getmyseat:problem:inventory-unavailable");
	}

}
