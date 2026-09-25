package com.getmyseat.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the Keycloak role-granting adapter with {@link FakeRoleGrants}. */
@TestConfiguration(proxyBeanMethods = false)
class FakeRoleGrantsConfiguration {

	@Bean
	@Primary
	FakeRoleGrants fakeRoleGrants() {
		return new FakeRoleGrants();
	}

}
