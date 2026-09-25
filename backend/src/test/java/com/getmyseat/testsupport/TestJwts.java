package com.getmyseat.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Keycloak in API tests: mints signed access tokens per request and serves the matching JWK
 * set, so the backend validates them through its real resource-server configuration.
 */
public final class TestJwts implements AutoCloseable {

	public static final String ISSUER = "https://keycloak.test/realms/getmyseat";

	public static final String AUDIENCE = "getmyseat-api";

	private static final String KEY_ID = "test-signing-key";

	private final RSAKey signingKey = generateKey();

	// Same key id as the trusted key, so a forged token is rejected on its signature, not on a missing key.
	private final RSAKey untrustedKey = generateKey();

	private final HttpServer jwksServer;

	TestJwts() {
		try {
			this.jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		byte[] jwks = new JWKSet(this.signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
		this.jwksServer.createContext("/certs", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, jwks.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(jwks);
			}
		});
		this.jwksServer.start();
	}

	String jwkSetUri() {
		return "http://127.0.0.1:" + this.jwksServer.getAddress().getPort() + "/certs";
	}

	/** A token for a caller with a random subject, the given realm roles and Keycloak-style name and email claims. */
	public Token as(String... realmRoles) {
		return new Token().roles(realmRoles);
	}

	public Token customer() {
		return as("CUSTOMER");
	}

	public Token organizer() {
		return as("CUSTOMER", "ORGANIZER");
	}

	public Token admin() {
		return as("CUSTOMER", "ADMIN");
	}

	@Override
	public void close() {
		this.jwksServer.stop(0);
	}

	private static RSAKey generateKey() {
		try {
			return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public final class Token {

		private final Map<String, Object> claims = new LinkedHashMap<>();

		private Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);

		private RSAKey key = TestJwts.this.signingKey;

		private Token() {
			this.claims.put("iss", ISSUER);
			this.claims.put("aud", AUDIENCE);
			this.claims.put("sub", UUID.randomUUID().toString());
			this.claims.put("name", "Test Caller");
			this.claims.put("preferred_username", "test-caller");
			this.claims.put("email", "test-caller@getmyseat.test");
		}

		public Token subject(Object subject) {
			return claim("sub", subject.toString());
		}

		public Token name(String name) {
			return claim("name", name);
		}

		public Token email(String email) {
			return claim("email", email);
		}

		public Token roles(String... realmRoles) {
			return claim("realm_access", Map.of("roles", List.of(realmRoles)));
		}

		public Token issuer(String issuer) {
			return claim("iss", issuer);
		}

		public Token audience(String audience) {
			return claim("aud", audience);
		}

		/** Sets a claim; {@code null} removes it. */
		public Token claim(String name, Object value) {
			if (value == null) {
				this.claims.remove(name);
			}
			else {
				this.claims.put(name, value);
			}
			return this;
		}

		public Token expired() {
			this.expiresAt = Instant.now().minus(10, ChronoUnit.MINUTES);
			return this;
		}

		public Token signedByUntrustedKey() {
			this.key = TestJwts.this.untrustedKey;
			return this;
		}

		public String subject() {
			return (String) this.claims.get("sub");
		}

		/** The serialized token, ready for an {@code Authorization: Bearer} header. */
		public String encode() {
			JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issueTime(Date.from(this.expiresAt.minus(5, ChronoUnit.MINUTES)))
				.expirationTime(Date.from(this.expiresAt));
			this.claims.forEach(builder::claim);
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), builder.build());
			try {
				jwt.sign(new RSASSASigner(this.key));
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
			return jwt.serialize();
		}

	}

}
