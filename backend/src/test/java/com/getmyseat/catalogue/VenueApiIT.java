package com.getmyseat.catalogue;

import static com.getmyseat.catalogue.VenueApi.assertValidationProblem;
import static com.getmyseat.catalogue.VenueApi.id;
import static com.getmyseat.catalogue.VenueApi.labels;
import static com.getmyseat.catalogue.VenueApi.read;
import static com.getmyseat.catalogue.VenueApi.seatsUri;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** An Organizer drafting a Venue and its Section layout. */
@ApiIntegrationTest
class VenueApiIT {

	static final String VENUE = VenueApi.VENUE;

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	VenueApi api;

	String organizer;

	@BeforeEach
	void setUp() {
		api = new VenueApi(client, jwts);
		organizer = jwts.organizer().encode();
	}

	@Test
	void anOrganizerDraftsAVenueWithSeatedAndGeneralAdmissionSections() {
		String venue = createVenue(organizer);

		send("POST", "/api/v1/venues/" + venue + "/sections", organizer, """
				{ "name": "Balcony", "kind": "SEATED",
				  "rows": [ { "label": "A", "seatCount": 3 }, { "label": "B", "seatNumbers": [1, 2, 5] } ] }
				""")
			.expectStatus()
			.isCreated()
			.expectBody()
			.jsonPath("$.name")
			.isEqualTo("Balcony")
			.jsonPath("$.kind")
			.isEqualTo("SEATED")
			.jsonPath("$.capacity")
			.doesNotExist()
			.jsonPath("$.seats[*].label")
			.value(labels -> assertThat((List<Object>) labels).containsExactly("A1", "A2", "A3", "B1", "B2", "B5"));

		send("POST", "/api/v1/venues/" + venue + "/sections", organizer, """
				{ "name": "Standing", "kind": "GENERAL_ADMISSION", "capacity": 2000 }
				""")
			.expectStatus()
			.isCreated()
			.expectBody()
			.jsonPath("$.capacity")
			.isEqualTo(2000)
			.jsonPath("$.seats.length()")
			.isEqualTo(0);

		JsonNode detail = read(get("/api/v1/venues/" + venue, organizer).expectStatus().isOk());
		assertThat(detail.path("status").asString()).isEqualTo("DRAFT");
		assertThat(detail.path("name").asString()).isEqualTo("NSCI Dome");
		assertThat(detail.path("timeZone").asString()).isEqualTo("Asia/Kolkata");
		assertThat(detail.path("sections")).hasSize(2);
		JsonNode seat = detail.path("sections").get(0).path("seats").get(0);
		assertThat(seat.path("id").asString()).isNotEmpty();
		assertThat(seat.path("row").asString()).isEqualTo("A");
		assertThat(seat.path("number").asInt()).isEqualTo(1);

		get("/api/v1/venues/mine", organizer).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content[?(@.id == '" + venue + "')].status")
			.isEqualTo(List.of("DRAFT"));
	}

	@Test
	void sectionNamesAreUniqueWithinAVenueIgnoringCase() {
		String venue = createVenue(organizer);
		addSection(venue, "{ \"name\": \"Balcony\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 10 }")
			.expectStatus()
			.isCreated();

		addSection(venue, "{ \"name\": \"balcony\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 10 }")
			.expectStatus()
			.isEqualTo(409);

		// Another Venue can reuse the name.
		addSection(createVenue(organizer), "{ \"name\": \"Balcony\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 10 }")
			.expectStatus()
			.isCreated();
	}

	@Test
	void seatLabelsAreUniqueWithinASection() {
		String venue = createVenue(organizer);
		String section = id(addSection(venue, """
				{ "name": "Stalls", "kind": "SEATED", "rows": [ { "label": "A", "seatCount": 5 } ] }
				""").expectStatus().isCreated());

		assertValidationProblem(send("POST", seatsUri(venue, section), organizer, """
				{ "rows": [ { "label": "a", "seatNumbers": [5, 6] } ] }
				"""), "rows");
		assertValidationProblem(addSection(venue, """
				{ "name": "Circle", "kind": "SEATED", "rows": [ { "label": "B", "seatNumbers": [1, 1] } ] }
				"""), "rows");

		// Adding row A again with new numbers is fine.
		send("POST", seatsUri(venue, section), organizer, """
				{ "rows": [ { "label": "A", "seatNumbers": [6, 7] } ] }
				""")
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.seats.length()")
			.isEqualTo(7);
	}

	@Test
	void generalAdmissionNeedsACapacityAboveZeroAndHasNoSeats() {
		String venue = createVenue(organizer);

		assertValidationProblem(addSection(venue, "{ \"name\": \"Pit\", \"kind\": \"GENERAL_ADMISSION\" }"),
				"capacity");
		assertValidationProblem(
				addSection(venue, "{ \"name\": \"Pit\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 0 }"),
				"capacity");
		assertValidationProblem(addSection(venue, """
				{ "name": "Pit", "kind": "GENERAL_ADMISSION", "capacity": 100, "rows": [ { "label": "A", "seatCount": 2 } ] }
				"""), "rows");

		String pit = id(addSection(venue, "{ \"name\": \"Pit\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 100 }")
			.expectStatus()
			.isCreated());
		assertValidationProblem(send("POST", seatsUri(venue, pit), organizer, """
				{ "rows": [ { "label": "A", "seatCount": 2 } ] }
				"""), "rows");
	}

	@Test
	void aSeatedSectionHasNoCapacity() {
		assertValidationProblem(addSection(createVenue(organizer),
				"{ \"name\": \"Stalls\", \"kind\": \"SEATED\", \"capacity\": 100 }"), "capacity");
	}

	@Test
	void rowsAreALabelOfLettersAndEitherACountOrNumbers() {
		String venue = createVenue(organizer);
		for (String row : List.of("{ \"label\": \"A1\", \"seatCount\": 2 }", "{ \"label\": \"A\" }",
				"{ \"label\": \"A\", \"seatCount\": 2, \"seatNumbers\": [1] }", "{ \"label\": \"A\", \"seatCount\": 0 }",
				"{ \"label\": \"A\", \"seatCount\": 501 }", "{ \"label\": \"A\", \"seatNumbers\": [0] }",
				"{ \"label\": \"ABCD\", \"seatCount\": 2 }")) {
			assertValidationProblem(
					addSection(venue, "{ \"name\": \"S\", \"kind\": \"SEATED\", \"rows\": [" + row + "] }"),
					"rows");
		}
	}

	@Test
	void aVenueNeedsItsDetailsAndARealTimeZone() {
		assertValidationProblem(send("POST", "/api/v1/venues", organizer, """
				{ "name": "NSCI Dome", "address": "Worli", "city": "Mumbai", "timeZone": "Mumbai/Worli" }
				"""), "timeZone");

		send("POST", "/api/v1/venues", organizer, """
				{ "name": " ", "address": "", "city": "Mumbai", "timeZone": "Asia/Kolkata" }
				""")
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.errors[*].field")
			.value(fields -> assertThat((List<Object>) fields).containsExactlyInAnyOrder("name", "address"));
	}

	@Test
	void theOwnerEditsDetailsSectionsAndSeatsWhileDraft() {
		String venue = createVenue(organizer);
		String stalls = id(addSection(venue, """
				{ "name": "Stalls", "kind": "SEATED", "rows": [ { "label": "A", "seatCount": 3 } ] }
				""").expectStatus().isCreated());
		String pit = id(addSection(venue, "{ \"name\": \"Pit\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 50 }")
			.expectStatus()
			.isCreated());

		send("PUT", "/api/v1/venues/" + venue, organizer, """
				{ "name": "Dome", "address": "Worli", "city": "Mumbai", "timeZone": "Asia/Kolkata" }
				""")
			.expectStatus()
			.isOk();
		send("PUT", "/api/v1/venues/" + venue + "/sections/" + pit, organizer, """
				{ "name": "Front Pit", "capacity": 80 }
				""")
			.expectStatus()
			.isOk();
		String seat = read(get("/api/v1/venues/" + venue, organizer).expectStatus().isOk()).path("sections")
			.get(0)
			.path("seats")
			.get(1)
			.path("id")
			.asString();
		send("DELETE", seatsUri(venue, stalls) + "/" + seat, organizer, "").expectStatus().isNoContent();

		JsonNode detail = read(get("/api/v1/venues/" + venue, organizer).expectStatus().isOk());
		assertThat(detail.path("name").asString()).isEqualTo("Dome");
		assertThat(labels(detail.path("sections").get(0))).containsExactly("A1", "A3");
		assertThat(detail.path("sections").get(1).path("name").asString()).isEqualTo("Front Pit");
		assertThat(detail.path("sections").get(1).path("capacity").asInt()).isEqualTo(80);

		send("DELETE", "/api/v1/venues/" + venue + "/sections/" + pit, organizer, "").expectStatus().isNoContent();
		assertThat(read(get("/api/v1/venues/" + venue, organizer)).path("sections")).hasSize(1);
	}

	@Test
	void onlyOrganizersManageVenues() {
		send("POST", "/api/v1/venues", jwts.customer().encode(), VENUE).expectStatus().isForbidden();
		get("/api/v1/venues/mine", jwts.customer().encode()).expectStatus().isForbidden();
		send("POST", "/api/v1/venues", null, VENUE).expectStatus().isUnauthorized();
		get("/api/v1/venues/mine", null).expectStatus().isUnauthorized();
	}

	@Test
	void theOwnersListIsPaginated() {
		String owner = jwts.organizer().encode();
		for (int i = 0; i < 3; i++) {
			createVenue(owner);
		}
		get("/api/v1/venues/mine?size=2&sort=name", owner).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content.length()")
			.isEqualTo(2)
			.jsonPath("$.page.totalElements")
			.isEqualTo(3);
		get("/api/v1/venues/mine?sort=ownerSubject", owner).expectStatus().isBadRequest();
	}

	String createVenue(String token) {
		return api.createVenue(token);
	}

	RestTestClient.ResponseSpec addSection(String venue, String body) {
		return api.addSection(venue, organizer, body);
	}

	RestTestClient.ResponseSpec send(String method, String uri, String token, String body) {
		return api.send(method, uri, token, body);
	}

	RestTestClient.ResponseSpec get(String uri, String token) {
		return api.get(uri, token);
	}

}
