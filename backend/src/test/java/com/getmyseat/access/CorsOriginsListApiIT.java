package com.getmyseat.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.StatusAssertions;

import com.getmyseat.testsupport.ApiIntegrationTest;

/** Sets the environment variable, not the Spring key, so the placeholder and comma-splitting are covered too. */
@ApiIntegrationTest
@TestPropertySource(properties = "CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com")
class CorsOriginsListApiIT {

	@Autowired
	RestTestClient client;

	@ParameterizedTest
	@ValueSource(strings = { "https://app.example.com", "https://admin.example.com" })
	void allowsEveryOriginInTheList(String origin) {
		preflightFrom(origin).isOk();
	}

	@Test
	void replacesTheDefaultOrigin() {
		preflightFrom("http://localhost:5173").isForbidden();
	}

	private StatusAssertions preflightFrom(String origin) {
		return client.options()
			.uri("/api/v1/me")
			.header(HttpHeaders.ORIGIN, origin)
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
			.exchange()
			.expectStatus();
	}

}
