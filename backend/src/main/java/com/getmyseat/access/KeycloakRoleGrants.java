package com.getmyseat.access;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Grants realm roles through the Keycloak Admin REST API, signed in as the backend's confidential client with the
 * client-credentials grant. Keycloak's role mapping is idempotent, so adding a role the user has is a no-op.
 * <p>
 * The service-account token and the role lookups are cached, so a grant is usually a single call. That matters
 * because approval holds a database row lock while it waits on Keycloak.
 */
@Component
class KeycloakRoleGrants implements RoleGrants {

	/** Refresh the service-account token this long before Keycloak says it expires. */
	private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(10);

	/** Where Keycloak is and how the backend signs in to its Admin API. */
	@ConfigurationProperties("getmyseat.keycloak")
	record Properties(URI url, String realm, String clientId, String clientSecret, Duration timeout) {
	}

	private record AccessToken(String value, Instant refreshAfter) {
	}

	private final Properties properties;

	private final RestClient http;

	private final Clock clock = Clock.systemUTC();

	private final Map<Role, Map<?, ?>> realmRoles = new ConcurrentHashMap<>();

	private volatile @Nullable AccessToken token;

	KeycloakRoleGrants(Properties properties) {
		this.properties = properties;
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
		requestFactory.setReadTimeout(properties.timeout());
		this.http = RestClient.builder().baseUrl(properties.url().toString()).requestFactory(requestFactory).build();
	}

	@Override
	public void grant(UUID subject, Role role) {
		try {
			try {
				addRealmRole(subject, role);
			}
			catch (HttpClientErrorException.Unauthorized ex) {
				// The cached token may have been revoked; sign in again once before giving up.
				this.token = null;
				addRealmRole(subject, role);
			}
		}
		catch (RestClientException ex) {
			throw new RoleGrantException(RoleGrantException.Reason.UNAVAILABLE,
					"Keycloak didn't grant " + role + " to " + subject, ex);
		}
	}

	private void addRealmRole(UUID subject, Role role) {
		String accessToken = accessToken();
		Map<?, ?> realmRole = this.realmRoles.computeIfAbsent(role, r -> this.http.get()
			.uri("/admin/realms/{realm}/roles/{role}", this.properties.realm(), r.name())
			.headers(h -> h.setBearerAuth(accessToken))
			.retrieve()
			.body(Map.class));
		try {
			this.http.post()
				.uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", this.properties.realm(), subject)
				.headers(h -> h.setBearerAuth(accessToken))
				.contentType(MediaType.APPLICATION_JSON)
				.body(List.of(realmRole))
				.retrieve()
				.toBodilessEntity();
		}
		catch (HttpClientErrorException.NotFound ex) {
			throw new RoleGrantException(RoleGrantException.Reason.UNKNOWN_USER,
					"Keycloak has no user " + subject, ex);
		}
	}

	private String accessToken() {
		AccessToken current = this.token;
		if (current != null && this.clock.instant().isBefore(current.refreshAfter())) {
			return current.value();
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", this.properties.clientId());
		form.add("client_secret", this.properties.clientSecret());
		Instant requestedAt = this.clock.instant();
		Map<?, ?> response = this.http.post()
			.uri("/realms/{realm}/protocol/openid-connect/token", this.properties.realm())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(Map.class);
		if (response == null || !(response.get("access_token") instanceof String value)) {
			throw new RestClientException("Keycloak's token response has no access_token");
		}
		long expiresIn = (response.get("expires_in") instanceof Number seconds) ? seconds.longValue() : 0;
		this.token = new AccessToken(value, requestedAt.plusSeconds(expiresIn).minus(TOKEN_EXPIRY_MARGIN));
		return value;
	}

}
