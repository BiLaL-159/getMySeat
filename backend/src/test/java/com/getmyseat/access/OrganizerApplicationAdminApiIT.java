package com.getmyseat.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.FakeRoleGrants;
import com.getmyseat.testsupport.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ApiIntegrationTest
class OrganizerApplicationAdminApiIT {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	@Autowired
	FakeRoleGrants roleGrants;

	final UUID adminSubject = UUID.randomUUID();

	String admin;

	@BeforeEach
	void setUp() {
		roleGrants.reset();
		admin = jwts.admin().subject(adminSubject).encode();
	}

	@Test
	void theQueueListsPendingApplicationsOldestFirst() {
		String first = apply(UUID.randomUUID());
		String second = apply(UUID.randomUUID());
		String decided = apply(UUID.randomUUID());
		reject(decided, "Not a real company").expectStatus().isOk();

		List<String> pending = queueIds("PENDING");
		assertThat(pending).contains(first, second).doesNotContain(decided);
		assertThat(pending.indexOf(first)).isLessThan(pending.indexOf(second));
		assertThat(queueIds("REJECTED")).contains(decided).doesNotContain(first, second);
	}

	@Test
	void theQueueIsPaginated() {
		apply(UUID.randomUUID());
		apply(UUID.randomUUID());

		client.get()
			.uri("/api/v1/admin/organizer-applications?status=PENDING&size=1")
			.headers(h -> h.setBearerAuth(admin))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content.length()")
			.isEqualTo(1)
			.jsonPath("$.page.size")
			.isEqualTo(1)
			.jsonPath("$.page.totalElements")
			.value(total -> assertThat(((Number) total).intValue()).isGreaterThanOrEqualTo(2));
	}

	@Test
	void approvingGrantsTheOrganizerRoleAndRecordsTheDecision() {
		UUID applicant = UUID.randomUUID();
		String id = apply(applicant);

		approve(id).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("APPROVED")
			.jsonPath("$.decidedBy")
			.isEqualTo(adminSubject.toString())
			.jsonPath("$.decidedAt")
			.isNotEmpty();

		assertThat(roleGrants.grants()).containsExactly(new FakeRoleGrants.Grant(applicant, Role.ORGANIZER));
		mineOf(applicant).jsonPath("$.status").isEqualTo("APPROVED");
	}

	@Test
	void rejectingRecordsTheReasonAndTheApplicantCanApplyAgain() {
		UUID applicant = UUID.randomUUID();
		String id = apply(applicant);

		reject(id, "Please add your GST number.").expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("REJECTED")
			.jsonPath("$.rejectionReason")
			.isEqualTo("Please add your GST number.")
			.jsonPath("$.decidedBy")
			.isEqualTo(adminSubject.toString());

		assertThat(roleGrants.grants()).isEmpty();
		mineOf(applicant).jsonPath("$.rejectionReason").isEqualTo("Please add your GST number.");

		String again = apply(applicant);
		assertThat(again).isNotEqualTo(id);
		mineOf(applicant).jsonPath("$.status").isEqualTo("PENDING");
	}

	@Test
	void anApprovedApplicantWhoseTokenIsNotYetRefreshedCannotApplyAgain() {
		UUID applicant = UUID.randomUUID();
		approve(apply(applicant)).expectStatus().isOk();

		// Their token still says CUSTOMER only until it's refreshed.
		client.post()
			.uri("/api/v1/organizer-applications")
			.headers(h -> h.setBearerAuth(jwts.customer().subject(applicant).encode()))
			.contentType(MediaType.APPLICATION_JSON)
			.body(OrganizerApplicationApiIT.APPLICATION)
			.exchange()
			.expectStatus()
			.isEqualTo(409);
		mineOf(applicant).jsonPath("$.status").isEqualTo("APPROVED");
	}

	@Test
	void anApplicantMissingFromKeycloakIsAConflictNotARetry() {
		String id = apply(UUID.randomUUID());
		roleGrants.failWith(RoleGrantException.Reason.UNKNOWN_USER);

		approve(id).expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.detail")
			.value(detail -> assertThat((String) detail).contains("no longer exists"));
	}

	@Test
	void rejectingNeedsAReason() {
		String id = apply(UUID.randomUUID());

		reject(id, " ").expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.errors[0].field")
			.isEqualTo("reason");
	}

	@Test
	void decidingAnAlreadyDecidedApplicationIsAConflict() {
		String approved = apply(UUID.randomUUID());
		approve(approved).expectStatus().isOk();
		String rejected = apply(UUID.randomUUID());
		reject(rejected, "No").expectStatus().isOk();

		approve(approved).expectStatus().isEqualTo(409);
		reject(approved, "Changed my mind").expectStatus().isEqualTo(409);
		approve(rejected).expectStatus()
			.isEqualTo(409)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:conflict");
		assertThat(roleGrants.grants()).hasSize(1);
	}

	@Test
	void whenKeycloakFailsTheApplicationStaysPendingAndTheAdminCanRetry() {
		UUID applicant = UUID.randomUUID();
		String id = apply(applicant);
		roleGrants.failing(true);

		approve(id).expectStatus()
			.isEqualTo(503)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:upstream-unavailable");
		mineOf(applicant).jsonPath("$.status").isEqualTo("PENDING");

		roleGrants.failing(false);
		approve(id).expectStatus().isOk();
		assertThat(roleGrants.grants()).containsExactly(new FakeRoleGrants.Grant(applicant, Role.ORGANIZER));
	}

	@Test
	void twoAdminsRacingOnlyOneDecisionWins() throws Exception {
		String id = apply(UUID.randomUUID());
		CountDownLatch grantStarted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		roleGrants.duringGrant(() -> {
			grantStarted.countDown();
			await(release);
		});

		String otherAdmin = jwts.admin().encode();
		try {
			CompletableFuture<Integer> approval = CompletableFuture.supplyAsync(() -> status(approve(id)));
			assertThat(grantStarted.await(10, TimeUnit.SECONDS)).as("approval reached Keycloak").isTrue();
			// The rejection arrives while the approval is still talking to Keycloak.
			CompletableFuture<Integer> rejection = CompletableFuture
				.supplyAsync(() -> status(decide(id, "reject", otherAdmin, "{ \"reason\": \"Too late\" }")));
			Thread.sleep(300);
			release.countDown();

			assertThat(List.of(approval.get(20, TimeUnit.SECONDS), rejection.get(20, TimeUnit.SECONDS)))
				.containsExactly(200, 409);
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void onlyAdminsCanSeeTheQueueOrDecide() {
		String id = apply(UUID.randomUUID());
		for (String token : List.of(jwts.customer().encode(), jwts.organizer().encode())) {
			client.get()
				.uri("/api/v1/admin/organizer-applications")
				.headers(h -> h.setBearerAuth(token))
				.exchange()
				.expectStatus()
				.isForbidden();
			decide(id, "approve", token, "{}").expectStatus().isForbidden();
			decide(id, "reject", token, "{ \"reason\": \"x\" }").expectStatus().isForbidden();
		}
		client.get().uri("/api/v1/admin/organizer-applications").exchange().expectStatus().isUnauthorized();
		assertThat(roleGrants.grants()).isEmpty();
	}

	@Test
	void decidingAnUnknownApplicationIsNotFound() {
		approve(UUID.randomUUID().toString()).expectStatus().isNotFound();
	}

	private String apply(UUID applicant) {
		byte[] body = client.post()
			.uri("/api/v1/organizer-applications")
			.headers(h -> h.setBearerAuth(jwts.customer().subject(applicant).encode()))
			.contentType(MediaType.APPLICATION_JSON)
			.body(OrganizerApplicationApiIT.APPLICATION)
			.exchange()
			.expectStatus()
			.isCreated()
			.expectBody()
			.returnResult()
			.getResponseBody();
		return JSON.readTree(body).path("id").asString();
	}

	private RestTestClient.BodyContentSpec mineOf(UUID applicant) {
		return client.get()
			.uri("/api/v1/organizer-applications/mine")
			.headers(h -> h.setBearerAuth(jwts.customer().subject(applicant).encode()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody();
	}

	private List<String> queueIds(String status) {
		byte[] body = client.get()
			.uri("/api/v1/admin/organizer-applications?status={status}&size=100", status)
			.headers(h -> h.setBearerAuth(admin))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult()
			.getResponseBody();
		List<String> ids = new ArrayList<>();
		for (JsonNode application : JSON.readTree(body).path("content")) {
			assertThat(application.path("status").asString()).isEqualTo(status);
			ids.add(application.path("id").asString());
		}
		return ids;
	}

	private RestTestClient.ResponseSpec approve(String id) {
		return decide(id, "approve", admin, "{}");
	}

	private RestTestClient.ResponseSpec reject(String id, String reason) {
		return decide(id, "reject", admin, "{ \"reason\": \"" + reason + "\" }");
	}

	private RestTestClient.ResponseSpec decide(String id, String decision, String token, String body) {
		return client.post()
			.uri("/api/v1/admin/organizer-applications/{id}/{decision}", id, decision)
			.headers(h -> h.setBearerAuth(token))
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.exchange();
	}

	private static int status(RestTestClient.ResponseSpec response) {
		return response.returnResult(String.class).getStatus().value();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
