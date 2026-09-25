package com.getmyseat.access;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps the GetMySeat roles in Keycloak's {@code realm_access.roles} claim to {@code ROLE_*} authorities.
 * Other realm roles, such as {@code offline_access}, are ignored.
 */
final class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Collection<GrantedAuthority> authorities = realmRoles(jwt).stream()
			.<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
			.toList();
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}

	static Set<Role> realmRoles(Jwt jwt) {
		Set<Role> roles = EnumSet.noneOf(Role.class);
		Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
		if (realmAccess != null && realmAccess.get("roles") instanceof List<?> names) {
			for (Role role : Role.values()) {
				if (names.contains(role.name())) {
					roles.add(role);
				}
			}
		}
		return roles;
	}

}
