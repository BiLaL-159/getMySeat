package com.getmyseat.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

@ApiIntegrationTest
class OrganizerApplicationApiIT {

	static final String APPLICATION = """
			{ "organisationName": "Sunburn Live", "contactPhone": "+91 98765 43210",
			  "description": "Music festivals in Goa and Pune." }
			""";

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	@Test
	void aCustomerSubmitsAnApplicationAndSeesItPending() {
		UUID subject = UUID.randomUUID();
		String token = jwts.customer().subject(subject).name("Casey Customer").email("casey@getmyseat.test").encode();

		client.post()
			.uri("/api/v1/organizer-applications")
			.headers(h -> h.setBearerAuth(token))
			.contentType(MediaType.APPLICATION_JSON)
			.body(APPLICATION)
			.exchange()
			.expectStatus()
			.isCreated()
			.expectHeader()
			.exists("Location")
			.expectBody()
			.jsonPath("$.id")
			.isNotEmpty()
			.jsonPath("$.status")
			.isEqualTo("PENDING")
			.jsonPath("$.applicantSubject")
			.isEqualTo(subject.toString())
			.jsonPath("$.applicantName")
			.isEqualTo("Casey Customer")
			.jsonPath("$.applicantEmail")
			.isEqualTo("casey@getmyseat.test")
			.jsonPath("$.organisationName")
			.isEqualTo("Sunburn Live");

		client.get()
			.uri("/api/v1/organizer-applications/mine")
			.headers(h -> h.setBearerAuth(token))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("PENDING")
			.jsonPath("$.contactPhone")
			.isEqualTo("+91 98765 43210")
			.jsonPath("$.description")
			.isEqualTo("Music festivals in Goa and Pune.")
			.jsonPath("$.rejectionReason")
			.doesNotExist();
	}

	@Test
	void aSecondApplicationWhileOneIsPendingIsAConflict() {
		String token = jwts.customer().encode();
		submit(token).expectStatus().isCreated();

		submit(token).expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict");
	}

	@Test
	void anOrganizerCannotApply() {
		submit(jwts.organizer().encode()).expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict");
	}

	@Test
	void aVeryLongNameFromKeycloakIsAccepted() {
		// Keycloak allows 255 characters each for first and last name.
		String name = "N".repeat(255) + " " + "M".repeat(255);
		submit(jwts.customer().name(name).encode()).expectStatus()
			.isCreated()
			.expectBody()
			.jsonPath("$.applicantName")
			.isEqualTo(name);
	}

	@Test
	void anInvalidApplicationListsEachBadField() {
		client.post()
			.uri("/api/v1/organizer-applications")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.contentType(MediaType.APPLICATION_JSON)
			.body("""
					{ "organisationName": " ", "contactPhone": "call me", "description": "" }
					""")
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[*].field")
			.value(fields -> assertThat((List<Object>) fields).containsExactlyInAnyOrder("organisationName",
					"contactPhone", "description"));
	}

	@Test
	void aCustomerWhoNeverAppliedHasNoApplication() {
		client.get()
			.uri("/api/v1/organizer-applications/mine")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	@Test
	void applyingNeedsASignedInCaller() {
		client.post()
			.uri("/api/v1/organizer-applications")
			.contentType(MediaType.APPLICATION_JSON)
			.body(APPLICATION)
			.exchange()
			.expectStatus()
			.isUnauthorized();
	}

	private RestTestClient.ResponseSpec submit(String token) {
		return client.post()
			.uri("/api/v1/organizer-applications")
			.headers(h -> h.setBearerAuth(token))
			.contentType(MediaType.APPLICATION_JSON)
			.body(APPLICATION)
			.exchange();
	}

}
