package com.getmyseat.testsupport;

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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real Keycloak booted from the same realm export that docker-compose.yml imports, plus helpers to sign in as
 * the seed users with the dev CLI client and read the resulting access tokens.
 */
public final class RealmKeycloak {

	static final Path REALM_EXPORT = Path.of("..", "infra", "keycloak", "getmyseat-realm.json");

	static final JsonMapper JSON = JsonMapper.builder().build();

	private RealmKeycloak() {
	}

	/** A Keycloak container that imports the committed realm export. Keep the image in sync with docker-compose.yml. */
	@SuppressWarnings("resource")
	public static GenericContainer<?> container() {
		return new GenericContainer<>("quay.io/keycloak/keycloak:26.7.4").withCommand("start-dev", "--import-realm")
			.withCopyFileToContainer(MountableFile.forHostPath(REALM_EXPORT),
					"/opt/keycloak/data/import/getmyseat-realm.json")
			.withExposedPorts(8080)
			.waitingFor(Wait.forHttp("/realms/getmyseat/.well-known/openid-configuration").forPort(8080));
	}

	/** Keycloak's base URL as seen from the host, which is also what issued tokens carry in {@code iss}. */
	public static String url(GenericContainer<?> keycloak) {
		return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
	}

	public static String issuer(GenericContainer<?> keycloak) {
		return url(keycloak) + "/realms/getmyseat";
	}

	/** Signs in as a seed user (password {@code password}) and returns the access token. */
	public static String passwordGrantToken(GenericContainer<?> keycloak, String username) throws Exception {
		String form = Map.of("grant_type", "password", "client_id", "getmyseat-dev-cli", "username", username,
				"password", "password")
			.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(issuer(keycloak) + "/protocol/openid-connect/token"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(form))
					.build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body()).path("access_token").asString();
	}

	public static JsonNode claimsOf(String accessToken) {
		return JSON.readTree(
				new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8));
	}

	public static List<String> realmRolesOf(String accessToken) {
		List<String> roles = new ArrayList<>();
		claimsOf(accessToken).path("realm_access").path("roles").forEach(r -> roles.add(r.asString()));
		return roles;
	}

}
