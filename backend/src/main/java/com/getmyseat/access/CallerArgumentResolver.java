package com.getmyseat.access;

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

/** Supplies {@link Caller} controller parameters from the authenticated token. */
final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return Caller.class.equals(parameter.getParameterType());
	}

	@Override
	public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token)) {
			throw new AuthenticationCredentialsNotFoundException("No signed-in caller");
		}
		return from(token.getToken());
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
