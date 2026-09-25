package com.getmyseat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots Keycloak with the same realm export that docker-compose.yml imports, so a broken or stale
 * export fails the build.
 */
@Testcontainers
class KeycloakRealmIT {

	static final Path REALM_EXPORT = Path.of("..", "infra", "keycloak", "getmyseat-realm.json");

	static final JsonMapper JSON = JsonMapper.builder().build();

	// Keep the image in sync with docker-compose.yml.
	@Container
	static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.4")
		.withCommand("start-dev", "--import-realm")
		.withCopyFileToContainer(MountableFile.forHostPath(REALM_EXPORT), "/opt/keycloak/data/import/getmyseat-realm.json")
		.withExposedPorts(8080)
		.waitingFor(Wait.forHttp("/realms/getmyseat/.well-known/openid-configuration").forPort(8080));

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
		String form = Map.of("grant_type", "password", "client_id", "getmyseat-dev-cli", "username", username,
				"password", "password")
			.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		URI tokenUri = URI.create("http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)
				+ "/realms/getmyseat/protocol/openid-connect/token");
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(tokenUri)
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(form))
					.build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body()).path("access_token").asString();
	}

	private static JsonNode claimsOf(String accessToken) {
		return JSON.readTree(new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8));
	}

	private static List<String> realmRolesOf(String accessToken) {
		List<String> roles = new ArrayList<>();
		claimsOf(accessToken).path("realm_access").path("roles").forEach(r -> roles.add(r.asString()));
		return roles;
	}

}
