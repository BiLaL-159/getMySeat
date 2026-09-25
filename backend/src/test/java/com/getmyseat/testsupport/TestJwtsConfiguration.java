package com.getmyseat.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Points the resource server at {@link TestJwts} instead of Keycloak. */
@TestConfiguration(proxyBeanMethods = false)
class TestJwtsConfiguration {

	@Bean
	TestJwts testJwts() {
		return new TestJwts();
	}

	@Bean
	DynamicPropertyRegistrar testJwtsProperties(TestJwts testJwts) {
		return registry -> {
			registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwts.ISSUER);
			registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", testJwts::jwkSetUri);
			registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwts.AUDIENCE);
		};
	}

}
