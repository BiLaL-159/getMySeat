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
 * {@link FakeRoleGrants}, and the application's {@code Clock} is a {@link TestClock}. The Hold cleanup job runs only
 * at startup, so a test that moves the clock past a Hold's expiry sees only lazy expiry; a test of the job sets a
 * short {@code getmyseat.holds.cleanup-interval} with {@code @TestPropertySource}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "getmyseat.holds.cleanup-interval=1h")
@AutoConfigureRestTestClient
@Import({ TestcontainersConfiguration.class, TestJwtsConfiguration.class, FakeRoleGrantsConfiguration.class,
		TestClockConfiguration.class })
public @interface ApiIntegrationTest {

}
