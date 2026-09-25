package com.getmyseat.catalogue;

import static com.getmyseat.catalogue.VenueApi.read;
import static com.getmyseat.catalogue.VenueApi.seatIds;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;

/** Submitting a Venue, the Admin review, and who may see or change a Venue in each status. */
@ApiIntegrationTest
class VenueReviewApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	VenueApi api;

	String owner;

	String admin;

	final UUID adminSubject = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		api = new VenueApi(client, jwts);
		owner = api.jwts.organizer().encode();
		admin = api.jwts.admin().subject(adminSubject).encode();
	}

	@Test
	void aSubmittedVenueIsApprovedAndItsLayoutIsFixed() {
		String venue = draftWithLayout(owner);
		JsonNode before = read(api.get("/api/v1/venues/" + venue, owner));

		submit(venue, owner).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("PENDING_REVIEW");
		decide(venue, "approve", "{}").expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("APPROVED")
			.jsonPath("$.decidedBy")
			.isEqualTo(adminSubject.toString())
			.jsonPath("$.decidedAt")
			.isNotEmpty();

		JsonNode after = read(api.get("/api/v1/venues/" + venue, null).expectStatus().isOk());
		assertThat(seatIds(after)).isNotEmpty().isEqualTo(seatIds(before));

		String section = after.path("sections").get(0).path("id").asString();
		String seat = after.path("sections").get(0).path("seats").get(0).path("id").asString();
		for (var change : List.of(
				api.send("PUT", "/api/v1/venues/" + venue, owner, VenueApi.VENUE),
				api.send("POST", "/api/v1/venues/" + venue + "/sections", owner,
						"{ \"name\": \"New\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 5 }"),
				api.send("PUT", "/api/v1/venues/" + venue + "/sections/" + section, owner, "{ \"name\": \"X\" }"),
				api.send("DELETE", "/api/v1/venues/" + venue + "/sections/" + section, owner, ""),
				api.send("POST", VenueApi.seatsUri(venue, section), owner,
						"{ \"rows\": [ { \"label\": \"Z\", \"seatCount\": 1 } ] }"),
				api.send("DELETE", VenueApi.seatsUri(venue, section) + "/" + seat, owner, ""),
				submit(venue, owner))) {
			change.expectStatus().isEqualTo(409).expectBody().jsonPath("$.type").isEqualTo("urn:getmyseat:problem:conflict");
		}
		assertThat(seatIds(read(api.get("/api/v1/venues/" + venue, null)))).isEqualTo(seatIds(before));
	}

	@Test
	void aRejectedVenueCanBeFixedAndResubmitted() {
		String venue = draftWithLayout(owner);
		submit(venue, owner).expectStatus().isOk();

		decide(venue, "reject", "{ \"reason\": \"Add the balcony.\" }").expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("REJECTED")
			.jsonPath("$.rejectionReason")
			.isEqualTo("Add the balcony.");

		api.send("POST", "/api/v1/venues/" + venue + "/sections", owner, """
				{ "name": "Balcony", "kind": "SEATED", "rows": [ { "label": "A", "seatCount": 2 } ] }
				""").expectStatus().isCreated();
		submit(venue, owner).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("PENDING_REVIEW")
			.jsonPath("$.rejectionReason")
			.doesNotExist();
		decide(venue, "approve", "{}").expectStatus().isOk();
	}

	@Test
	void aVenueWaitingForReviewCannotChange() {
		String venue = draftWithLayout(owner);
		submit(venue, owner).expectStatus().isOk();

		api.send("PUT", "/api/v1/venues/" + venue, owner, VenueApi.VENUE).expectStatus().isEqualTo(409);
		submit(venue, owner).expectStatus().isEqualTo(409);
	}

	@Test
	void submittingNeedsACompleteLayout() {
		String empty = api.createVenue(owner);
		submit(empty, owner).expectStatus().isEqualTo(409);

		String seatless = api.createVenue(owner);
		api.send("POST", "/api/v1/venues/" + seatless + "/sections", owner,
				"{ \"name\": \"Stalls\", \"kind\": \"SEATED\" }").expectStatus().isCreated();
		submit(seatless, owner).expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.detail")
			.value(detail -> assertThat((String) detail).contains("Stalls"));
	}

	@Test
	void onlyAVenueWaitingForReviewCanBeDecided() {
		String draft = draftWithLayout(owner);
		decide(draft, "approve", "{}").expectStatus().isEqualTo(409);

		String approved = approvedVenue();
		decide(approved, "approve", "{}").expectStatus().isEqualTo(409);
		decide(approved, "reject", "{ \"reason\": \"Too late\" }").expectStatus().isEqualTo(409);

		decide(UUID.randomUUID().toString(), "approve", "{}").expectStatus().isNotFound();
	}

	@Test
	void rejectingNeedsAReason() {
		String venue = draftWithLayout(owner);
		submit(venue, owner).expectStatus().isOk();

		VenueApi.assertValidationProblem(decide(venue, "reject", "{ \"reason\": \"\" }"), "reason");
	}

	@Test
	void theReviewQueueShowsTheFullLayoutOldestSubmissionFirst() {
		String first = draftWithLayout(owner);
		String second = draftWithLayout(owner);
		submit(first, owner).expectStatus().isOk();
		submit(second, owner).expectStatus().isOk();

		JsonNode queue = read(api.get("/api/v1/admin/venues?status=PENDING_REVIEW&size=100", admin).expectStatus().isOk());
		List<String> ids = new ArrayList<>();
		for (JsonNode venue : queue.path("content")) {
			assertThat(venue.path("status").asString()).isEqualTo("PENDING_REVIEW");
			ids.add(venue.path("id").asString());
			if (venue.path("id").asString().equals(first)) {
				assertThat(venue.path("sections")).hasSize(2);
				assertThat(VenueApi.labels(venue.path("sections").get(0))).containsExactly("A1", "A2", "B1", "B2");
			}
		}
		assertThat(ids).contains(first, second);
		assertThat(ids.indexOf(first)).isLessThan(ids.indexOf(second));
	}

	@Test
	void onlyAdminsReview() {
		String venue = draftWithLayout(owner);
		submit(venue, owner).expectStatus().isOk();
		for (String token : List.of(owner, api.jwts.customer().encode())) {
			api.get("/api/v1/admin/venues", token).expectStatus().isForbidden();
			api.send("POST", "/api/v1/admin/venues/" + venue + "/approve", token, "{}").expectStatus().isForbidden();
		}
		api.get("/api/v1/admin/venues", null).expectStatus().isUnauthorized();
	}

	@Test
	void anotherOrganizerCannotSeeOrChangeSomeoneElsesUnapprovedVenue() {
		String venue = draftWithLayout(owner);
		String other = api.jwts.organizer().encode();

		api.get("/api/v1/venues/" + venue, other).expectStatus().isNotFound();
		api.get("/api/v1/venues/" + venue, null).expectStatus().isNotFound();
		api.send("PUT", "/api/v1/venues/" + venue, other, VenueApi.VENUE).expectStatus().isNotFound();
		api.send("POST", "/api/v1/venues/" + venue + "/sections", other,
				"{ \"name\": \"X\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 5 }").expectStatus().isNotFound();
		submit(venue, other).expectStatus().isNotFound();
		assertThat(read(api.get("/api/v1/venues/mine?size=100", other)).path("content").toString()).doesNotContain(venue);
	}

	@Test
	void anotherOrganizerCanSeeButNotChangeAnApprovedVenue() {
		String venue = approvedVenue();
		String other = api.jwts.organizer().encode();

		api.get("/api/v1/venues/" + venue, other).expectStatus().isOk();
		api.send("PUT", "/api/v1/venues/" + venue, other, VenueApi.VENUE)
			.expectStatus()
			.isForbidden()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:forbidden");
		api.send("POST", "/api/v1/venues/" + venue + "/sections", other,
				"{ \"name\": \"X\", \"kind\": \"GENERAL_ADMISSION\", \"capacity\": 5 }").expectStatus().isForbidden();
	}

	String approvedVenue() {
		String venue = draftWithLayout(owner);
		submit(venue, owner).expectStatus().isOk();
		decide(venue, "approve", "{}").expectStatus().isOk();
		return venue;
	}

	String draftWithLayout(String token) {
		return api.draftWithLayout(token);
	}

	RestTestClient.ResponseSpec submit(String venue, String token) {
		return api.submit(venue, token);
	}

	RestTestClient.ResponseSpec decide(String venue, String decision, String body) {
		return api.send("POST", "/api/v1/admin/venues/" + venue + "/" + decision, admin, body);
	}

}
