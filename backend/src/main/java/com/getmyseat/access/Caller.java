package com.getmyseat.access;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The signed-in caller. Declare it as a controller method parameter to receive it; the Keycloak subject is
 * the caller's identity everywhere in GetMySeat, so store {@link #subject()} to record ownership.
 *
 * @param subject the Keycloak subject id
 * @param name display name from the token, falling back to the username, then the subject
 * @param email email from the token, if Keycloak has one
 * @param roles the caller's GetMySeat roles
 */
public record Caller(UUID subject, String name, @Nullable String email, Set<Role> roles) {

	public Caller {
		roles = Set.copyOf(roles);
	}

	public boolean hasRole(Role role) {
		return this.roles.contains(role);
	}

}
