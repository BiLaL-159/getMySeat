package com.getmyseat.access;

import java.util.List;

import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Stateless OAuth2 resource server for Keycloak-issued JWTs. The issuer, JWK set and audience come from
 * {@code spring.security.oauth2.resourceserver.jwt.*}. Everything under the API needs a signed-in caller
 * unless it's listed as public here; role checks go on the controller method with {@code @PreAuthorize}. A public
 * endpoint still reads a bearer token when one is sent, so it can take an {@code Optional<Caller>}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(KeycloakRoleGrants.Properties.class)
class SecurityConfiguration implements WebMvcConfigurer {

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		BearerTokenAuthenticationEntryPoint bearerChallenge = new BearerTokenAuthenticationEntryPoint();
		// Security failures are rendered by the MVC exception handler so they share the API's ProblemDetail format.
		AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
			bearerChallenge.commence(request, response, ex);
			exceptionResolver.resolveException(request, response, null, ex);
		};
		AccessDeniedHandler accessDeniedHandler = (request, response, ex) -> exceptionResolver
			.resolveException(request, response, null, ex);

		http.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/actuator/health", "/actuator/health/**")
				.permitAll()
				.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
				.permitAll()
				.requestMatchers("/error")
				.permitAll()
				// Public catalogue reads. Owner-only reads under these paths still check the role on the method.
				.requestMatchers(HttpMethod.GET, "/api/v1/events/mine")
				.authenticated()
				.requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter()))
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDeniedHandler));
		return http.build();
	}

	/** Added by Spring Boot to the auto-configured JWT decoder, next to the issuer and audience checks. */
	@Bean
	OAuth2TokenValidator<Jwt> keycloakSubjectValidator() {
		return new KeycloakSubjectValidator();
	}

	/** {@link Caller} parameters come from the token, not the request, so they aren't in the OpenAPI spec. */
	@Bean
	ParameterCustomizer hideCallerParameters() {
		return (parameter, methodParameter) -> Caller.class
			.equals(methodParameter.nestedIfOptional().getNestedParameterType()) ? null : parameter;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new CallerArgumentResolver());
	}

}
