package com.getmyseat.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

@ApiIntegrationTest
class MeApiIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	@Test
	void returnsTheCallersIdentityAndGetMySeatRoles() {
		UUID subject = UUID.randomUUID();
		String token = jwts.as("CUSTOMER", "ORGANIZER", "offline_access", "default-roles-getmyseat")
			.subject(subject)
			.name("Olive Organizer")
			.email("organizer@getmyseat.local")
			.encode();

		client.get()
			.uri("/api/v1/me")
			.headers(h -> h.setBearerAuth(token))
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist(HttpHeaders.SET_COOKIE)
			.expectBody()
			.jsonPath("$.subject")
			.isEqualTo(subject.toString())
			.jsonPath("$.name")
			.isEqualTo("Olive Organizer")
			.jsonPath("$.email")
			.isEqualTo("organizer@getmyseat.local")
			.jsonPath("$.roles")
			.value(roles -> assertThat((List<Object>) roles).containsExactlyInAnyOrder("CUSTOMER", "ORGANIZER"));
	}

	@Test
	void fallsBackToTheUsernameWhenTheTokenHasNoName() {
		String token = jwts.customer().name(null).claim("preferred_username", "casey").encode();

		client.get()
			.uri("/api/v1/me")
			.headers(h -> h.setBearerAuth(token))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.name")
			.isEqualTo("casey");
	}

	@Test
	void rejectsACallWithoutATokenWithAProblemDetail() {
		client.get()
			.uri("/api/v1/me")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectHeader()
			.valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*")
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:unauthorized")
			.jsonPath("$.status")
			.isEqualTo(401)
			.jsonPath("$.title")
			.isEqualTo("Unauthorized");
	}

	static Stream<Arguments> unacceptableTokens() {
		return Stream.of(Arguments.of("expired", (UnaryOperator<TestJwts.Token>) TestJwts.Token::expired),
				Arguments.of("forged", (UnaryOperator<TestJwts.Token>) TestJwts.Token::signedByUntrustedKey),
				Arguments.of("wrong issuer",
						(UnaryOperator<TestJwts.Token>) t -> t.issuer("https://evil.test/realms/getmyseat")),
				Arguments.of("wrong audience", (UnaryOperator<TestJwts.Token>) t -> t.audience("account")),
				Arguments.of("subject is not a Keycloak id",
						(UnaryOperator<TestJwts.Token>) t -> t.subject("service-account-someone")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("unacceptableTokens")
	void rejectsAnUnacceptableTokenWithAProblemDetail(String description, UnaryOperator<TestJwts.Token> spoil) {
		String token = spoil.apply(jwts.customer()).encode();

		client.get()
			.uri("/api/v1/me")
			.headers(h -> h.setBearerAuth(token))
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:unauthorized");
	}

	@Test
	void rejectsAGarbageBearerTokenWithAProblemDetail() {
		client.get()
			.uri("/api/v1/me")
			.headers(h -> h.setBearerAuth("not-a-jwt"))
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:unauthorized");
	}

}
