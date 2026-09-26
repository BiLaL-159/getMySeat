package com.getmyseat.access;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;

@ApiIntegrationTest
class CorsApiIT {

	/** The default of {@code CORS_ALLOWED_ORIGINS}: the Vite dev server. */
	static final String SPA_ORIGIN = "http://localhost:5173";

	@Autowired
	RestTestClient client;

	@Test
	void letsTheSpaPreflightAnAuthenticatedCallWithoutAToken() {
		client.options()
			.uri("/api/v1/shows/{id}/holds", "00000000-0000-0000-0000-000000000000")
			.header(HttpHeaders.ORIGIN, SPA_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type, idempotency-key")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SPA_ORIGIN)
			.expectHeader()
			.value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, methods -> methods.contains("POST"))
			.expectHeader()
			.valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
					"(?i)(?=.*authorization)(?=.*content-type)(?=.*idempotency-key).*");
	}

	@Test
	void rejectsAPreflightFromAnyOtherOrigin() {
		client.options()
			.uri("/api/v1/me")
			.header(HttpHeaders.ORIGIN, "https://evil.test")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
			.exchange()
			.expectStatus()
			.isForbidden()
			.expectHeader()
			.doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
	}

	@Test
	void rejectsAPreflightAskingForAHeaderThatIsNotAllowed() {
		client.options()
			.uri("/api/v1/me")
			.header(HttpHeaders.ORIGIN, SPA_ORIGIN)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-something-else")
			.exchange()
			.expectStatus()
			.isForbidden();
	}

	@Test
	void exposesTheLocationHeaderToTheSpa() {
		client.get()
			.uri("/api/v1/events")
			.header(HttpHeaders.ORIGIN, SPA_ORIGIN)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SPA_ORIGIN)
			.expectHeader()
			.valueEquals(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.LOCATION);
	}

}
