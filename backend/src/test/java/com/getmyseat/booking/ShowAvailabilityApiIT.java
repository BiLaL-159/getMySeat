package com.getmyseat.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.catalogue.ShowApi;
import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** Publishing a Show creates its inventory, and anyone can see its live availability. */
@ApiIntegrationTest
class ShowAvailabilityApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	ShowApi shows;

	@BeforeEach
	void setUp() {
		this.shows = new ShowApi(this.client, this.jwts);
	}

	@Test
	void aPublishedShowHasEverySeatAvailableAndEveryGeneralAdmissionPlaceLeft() {
		String show = this.shows.publishedShow(jwts.organizer().encode(), this.shows.approvedVenue());
		JsonNode detail = ShowApi.read(this.shows.get("/api/v1/shows/" + show, null).expectStatus().isOk());

		JsonNode availability = availability(show);

		assertThat(availability.path("showId").asString()).isEqualTo(show);
		JsonNode seated = availability.path("sections").get(0);
		assertThat(seated.path("id").asString()).isEqualTo(detail.at("/sections/0/id").asString());
		assertThat(seated.path("kind").asString()).isEqualTo("SEATED");
		assertThat(seated.path("seats").valueStream().map(seat -> seat.path("id").asString()))
			.containsExactlyElementsOf(detail.at("/sections/0/seats").valueStream().map(s -> s.path("id").asString()).toList())
			.hasSize(4);
		assertThat(seated.path("seats").valueStream().map(seat -> seat.path("available").asBoolean())).containsOnly(true);
		JsonNode standing = availability.path("sections").get(1);
		assertThat(standing.path("id").asString()).isEqualTo(detail.at("/sections/1/id").asString());
		assertThat(standing.path("kind").asString()).isEqualTo("GENERAL_ADMISSION");
		assertThat(standing.path("capacity").asInt()).isEqualTo(500);
		assertThat(standing.path("available").asInt()).isEqualTo(500);
		assertThat(availability.path("sections")).hasSize(2);
	}

	@Test
	void everyShowAtAVenueGetsItsOwnInventory() {
		String token = jwts.organizer().encode();
		String venue = this.shows.approvedVenue();
		String first = this.shows.publishedShow(token, venue);
		String second = this.shows.publishedShow(token, venue);

		assertThat(availability(first).path("sections")).hasSize(2);
		assertThat(availability(second).path("sections")).hasSize(2);
	}

	@Test
	void aPublishThatFailsCreatesNoInventory() {
		String token = jwts.organizer().encode();
		String venue = this.shows.approvedVenue();
		String show = this.shows.draftShow(this.shows.publishedEvent(token), token, venue);
		List<String> sections = this.shows.sections(venue);
		this.shows.setPrices(show, token, ShowApi.prices(sections.subList(0, 1), 50_000)).expectStatus().isOk();
		this.shows.publish(show, token).expectStatus().isEqualTo(409);

		assertNotFound(this.shows.get("/api/v1/shows/" + show + "/availability", null));

		this.shows.priced(show, token, venue);
		this.shows.publish(show, token).expectStatus().isOk();
		assertThat(availability(show).at("/sections/1/available").asInt()).isEqualTo(500);
	}

	@Test
	void anyoneSignedInCanSeeAvailabilityToo() {
		String show = this.shows.publishedShow(jwts.organizer().encode(), this.shows.approvedVenue());

		this.shows.get("/api/v1/shows/" + show + "/availability", jwts.customer().encode()).expectStatus().isOk();
	}

	@Test
	void aDraftShowHasNoAvailabilityEvenForItsOwner() {
		String token = jwts.organizer().encode();
		String venue = this.shows.approvedVenue();
		String show = this.shows.priced(this.shows.draftShow(this.shows.publishedEvent(token), token, venue), token,
				venue);

		assertNotFound(this.shows.get("/api/v1/shows/" + show + "/availability", null));
		assertNotFound(this.shows.get("/api/v1/shows/" + show + "/availability", token));
	}

	@Test
	void anUnknownShowHasNoAvailability() {
		assertNotFound(this.shows.get("/api/v1/shows/00000000-0000-0000-0000-000000000000/availability", null));
	}

	private JsonNode availability(String show) {
		return ShowApi.read(this.shows.get("/api/v1/shows/" + show + "/availability", null).expectStatus().isOk());
	}

	private static void assertNotFound(RestTestClient.ResponseSpec response) {
		response.expectStatus()
			.isNotFound()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:not-found")
			.jsonPath("$.detail")
			.isEqualTo("No Show with that id.");
	}

}
