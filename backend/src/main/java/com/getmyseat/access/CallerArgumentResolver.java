package com.getmyseat.access;

import java.util.Optional;
import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link Caller} controller parameters from the authenticated token. Declare {@code Optional<Caller>} on
 * a public endpoint that behaves differently for a signed-in caller; it is empty for an anonymous one.
 */
final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return Caller.class.equals(parameter.nestedIfOptional().getNestedParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Optional<Caller> caller = (authentication instanceof JwtAuthenticationToken token)
				? Optional.of(from(token.getToken())) : Optional.empty();
		if (Optional.class.equals(parameter.getParameterType())) {
			return caller;
		}
		return caller.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No signed-in caller"));
	}

	static Caller from(Jwt jwt) {
		UUID subject = KeycloakSubjectValidator.parse(jwt.getSubject());
		String name = jwt.getClaimAsString("name");
		if (name == null || name.isBlank()) {
			name = jwt.getClaimAsString("preferred_username");
		}
		if (name == null || name.isBlank()) {
			name = jwt.getSubject();
		}
		return new Caller(subject, name, jwt.getClaimAsString("email"),
				KeycloakJwtAuthenticationConverter.realmRoles(jwt));
	}

}
