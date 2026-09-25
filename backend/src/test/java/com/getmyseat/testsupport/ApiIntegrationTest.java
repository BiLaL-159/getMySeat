package com.getmyseat.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

import com.getmyseat.TestcontainersConfiguration;

/**
 * An API integration test: the full application on a random port, PostgreSQL in Testcontainers with the real
 * Flyway migrations, and the real security filter chain fed by {@link TestJwts} tokens. Inject a
 * {@code RestTestClient} to call the API and {@link TestJwts} to sign in. Keycloak role grants go to
 * {@link FakeRoleGrants}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfiguration.class, TestJwtsConfiguration.class, FakeRoleGrantsConfiguration.class })
public @interface ApiIntegrationTest {

}
