package com.getmyseat.access;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects tokens whose {@code sub} isn't a Keycloak user id, since the subject is how we record ownership. */
final class KeycloakSubjectValidator implements OAuth2TokenValidator<Jwt> {

	private static final OAuth2Error INVALID_SUBJECT = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
			"The sub claim must be a Keycloak user id", null);

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		return (parse(jwt.getSubject()) != null) ? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(INVALID_SUBJECT);
	}

	static @Nullable UUID parse(@Nullable String subject) {
		if (subject == null) {
			return null;
		}
		try {
			return UUID.fromString(subject);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
