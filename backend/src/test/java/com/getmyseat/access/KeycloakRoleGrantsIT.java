package com.getmyseat.access;

import static com.getmyseat.testsupport.RealmKeycloak.passwordGrantToken;
import static com.getmyseat.testsupport.RealmKeycloak.realmRolesOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.getmyseat.TestcontainersConfiguration;
import com.getmyseat.testsupport.RealmKeycloak;

/**
 * The real Keycloak side of Organizer approval: the backend's service-account client grants roles through the
 * Admin API, and real Keycloak tokens pass the backend's issuer, audience and role mapping.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class KeycloakRoleGrantsIT {

	@Container
	static final GenericContainer<?> keycloak = RealmKeycloak.container();

	@DynamicPropertySource
	static void keycloakProperties(DynamicPropertyRegistry registry) {
		registry.add("getmyseat.keycloak.url", () -> RealmKeycloak.url(keycloak));
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> RealmKeycloak.issuer(keycloak));
	}

	@Autowired
	RoleGrants roleGrants;

	@Autowired
	RestTestClient client;

	@Test
	void grantsOrganizerToTheSeedCustomerAndGrantingAgainIsANoOp() throws Exception {
		String before = passwordGrantToken(keycloak, "customer");
		assertThat(realmRolesOf(before)).doesNotContain("ORGANIZER");
		UUID customer = UUID.fromString(RealmKeycloak.claimsOf(before).path("sub").asString());

		roleGrants.grant(customer, Role.ORGANIZER);
		assertThat(realmRolesOf(passwordGrantToken(keycloak, "customer"))).contains("ORGANIZER", "CUSTOMER");

		roleGrants.grant(customer, Role.ORGANIZER);
		assertThat(realmRolesOf(passwordGrantToken(keycloak, "customer"))).contains("ORGANIZER", "CUSTOMER");
	}

	@Test
	void grantingToAnUnknownUserFails() {
		assertThatExceptionOfType(RoleGrantException.class)
			.isThrownBy(() -> roleGrants.grant(UUID.randomUUID(), Role.ORGANIZER))
			.satisfies(ex -> assertThat(ex.reason()).isEqualTo(RoleGrantException.Reason.UNKNOWN_USER));
	}

	@Test
	void aRealKeycloakTokenIsAcceptedAndItsRolesAreMapped() throws Exception {
		String token = passwordGrantToken(keycloak, "platform-admin");

		client.get()
			.uri("/api/v1/me")
			.headers(h -> h.setBearerAuth(token))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.email")
			.isEqualTo("admin@getmyseat.local")
			.jsonPath("$.roles")
			.value(roles -> assertThat((List<Object>) roles).containsExactlyInAnyOrder("CUSTOMER", "ADMIN"));
	}

}
