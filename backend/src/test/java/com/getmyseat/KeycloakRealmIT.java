package com.getmyseat;

import static com.getmyseat.testsupport.RealmKeycloak.claimsOf;
import static com.getmyseat.testsupport.RealmKeycloak.realmRolesOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.getmyseat.testsupport.RealmKeycloak;

import tools.jackson.databind.JsonNode;

/**
 * Boots Keycloak with the same realm export that docker-compose.yml imports, so a broken or stale
 * export fails the build.
 */
@Testcontainers
class KeycloakRealmIT {

	@Container
	static final GenericContainer<?> keycloak = RealmKeycloak.container();

	@ParameterizedTest
	@CsvSource({ "customer, CUSTOMER", "organizer, ORGANIZER", "platform-admin, ADMIN" })
	void seedUserGetsTokenCarryingTheirRealmRole(String username, String role) throws Exception {
		assertThat(realmRolesOf(passwordGrantToken(username))).contains(role);
	}

	@Test
	void customerIsNotGrantedOrganizerOrAdmin() throws Exception {
		assertThat(realmRolesOf(passwordGrantToken("customer"))).doesNotContain("ORGANIZER", "ADMIN");
	}

	@Test
	void tokensAreIssuedForTheGetMySeatApi() throws Exception {
		JsonNode audience = claimsOf(passwordGrantToken("customer")).path("aud");
		List<String> audiences = new ArrayList<>();
		if (audience.isArray()) {
			audience.forEach(a -> audiences.add(a.asString()));
		}
		else {
			audiences.add(audience.asString());
		}
		assertThat(audiences).contains("getmyseat-api");
	}

	private static String passwordGrantToken(String username) throws Exception {
		return RealmKeycloak.passwordGrantToken(keycloak, username);
	}

}
