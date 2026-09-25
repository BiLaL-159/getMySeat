package com.getmyseat.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.getmyseat.testsupport.ApiIntegrationTest;
import com.getmyseat.testsupport.TestJwts;

@ApiIntegrationTest
class ApiConventionsIT {

	@Autowired
	RestTestClient client;

	@Autowired
	TestJwts jwts;

	@Test
	void aCallerWithoutTheRequiredRoleIsForbidden() {
		client.get()
			.uri("/api/v1/test-probe/admin-only")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isForbidden()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:forbidden")
			.jsonPath("$.status")
			.isEqualTo(403);
	}

	@Test
	void aCallerWithTheRequiredRoleIsAllowed() {
		client.get()
			.uri("/api/v1/test-probe/admin-only")
			.headers(h -> h.setBearerAuth(jwts.admin().encode()))
			.exchange()
			.expectStatus()
			.isOk();
	}

	@Test
	void anAnonymousCallerIsUnauthorizedRatherThanForbidden() {
		client.get()
			.uri("/api/v1/test-probe/admin-only")
			.exchange()
			.expectStatus()
			.isUnauthorized()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:unauthorized");
	}

	@Test
	void anInvalidBodyListsEveryFieldError() {
		client.post()
			.uri("/api/v1/test-probe/validated")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.contentType(MediaType.APPLICATION_JSON)
			.body("""
					{ "name": " ", "quantity": 0 }
					""")
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.status")
			.isEqualTo(400)
			.jsonPath("$.errors.length()")
			.isEqualTo(2)
			.jsonPath("$.errors[?(@.field == 'name')].message")
			.isNotEmpty()
			.jsonPath("$.errors[?(@.field == 'quantity')].message")
			.isNotEmpty();
	}

	@Test
	void anInvalidRequestParameterIsAValidationProblem() {
		client.get()
			.uri("/api/v1/test-probe/validated-param?quantity=0")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[0].field")
			.isEqualTo("quantity");
	}

	@Test
	void aMissingRequiredParameterIsAValidationProblem() {
		client.get()
			.uri("/api/v1/test-probe/validated-param")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[0].field")
			.isEqualTo("quantity");
	}

	@Test
	void aMalformedBodyIsABadRequest() {
		client.post()
			.uri("/api/v1/test-probe/validated")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.contentType(MediaType.APPLICATION_JSON)
			.body("{ not json")
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:malformed-request");
	}

	@Test
	void anUnknownRouteIsNotFound() {
		client.get()
			.uri("/api/v1/no-such-thing")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:not-found");
	}

	@Test
	void domainFailuresMapToTheirStatusAndType() {
		assertProblem("/api/v1/test-probe/not-found", 404, "urn:getmyseat:problem:not-found", "No Event with that id.");
		assertProblem("/api/v1/test-probe/conflict", 409, "urn:getmyseat:problem:conflict",
				"This application has already been decided.");
		assertProblem("/api/v1/test-probe/upstream-unavailable", 503, "urn:getmyseat:problem:upstream-unavailable",
				"Keycloak is unavailable. Nothing was changed; try again.");
	}

	@Test
	void anUnexpectedFailureIsAGenericServerErrorThatLeaksNothing() {
		String body = client.get()
			.uri("/api/v1/test-probe/boom")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isEqualTo(500)
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).contains("urn:getmyseat:problem:internal-error")
			.doesNotContain("secret internal detail")
			.doesNotContain("IllegalStateException")
			.doesNotContain("trace");
	}

	@Test
	void pagesDefaultToTwentyItemsInTheEnvelope() {
		client.get()
			.uri("/api/v1/test-probe/page")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content.length()")
			.isEqualTo(20)
			.jsonPath("$.content[0]")
			.isEqualTo("item-0")
			.jsonPath("$.page.number")
			.isEqualTo(0)
			.jsonPath("$.page.size")
			.isEqualTo(20)
			.jsonPath("$.page.totalElements")
			.isEqualTo(ConventionsProbeController.TOTAL_ITEMS)
			.jsonPath("$.page.totalPages")
			.isEqualTo(13);
	}

	@Test
	void pagesAreZeroBasedAndSizeIsCappedAtOneHundred() {
		client.get()
			.uri("/api/v1/test-probe/page?page=1&size=500")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content.length()")
			.isEqualTo(100)
			.jsonPath("$.content[0]")
			.isEqualTo("item-100")
			.jsonPath("$.page.number")
			.isEqualTo(1)
			.jsonPath("$.page.size")
			.isEqualTo(100);
	}

	@Test
	void sortingIsLimitedToTheAllowList() {
		client.get()
			.uri("/api/v1/test-probe/page?sort=name,desc")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isOk();

		client.get()
			.uri("/api/v1/test-probe/page?sort=ownerSubject")
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo("urn:getmyseat:problem:validation")
			.jsonPath("$.errors[0].field")
			.isEqualTo("sort");
	}

	@Test
	void theOpenApiSpecIsPublicAndDeclaresBearerJwt() {
		client.get()
			.uri("/v3/api-docs")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.components.securitySchemes['bearer-jwt'].type")
			.isEqualTo("http")
			.jsonPath("$.components.securitySchemes['bearer-jwt'].scheme")
			.isEqualTo("bearer")
			.jsonPath("$.components.securitySchemes['bearer-jwt'].bearerFormat")
			.isEqualTo("JWT")
			.jsonPath("$.paths['/api/v1/me']")
			.exists();
	}

	@Test
	void swaggerUiIsPublic() {
		client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
	}

	private void assertProblem(String uri, int status, String type, String detail) {
		client.get()
			.uri(uri)
			.headers(h -> h.setBearerAuth(jwts.customer().encode()))
			.exchange()
			.expectStatus()
			.isEqualTo(status)
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.type")
			.isEqualTo(type)
			.jsonPath("$.status")
			.isEqualTo(status)
			.jsonPath("$.detail")
			.isEqualTo(detail);
	}

}
