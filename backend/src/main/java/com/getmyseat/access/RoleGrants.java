package com.getmyseat.access;

import java.util.UUID;

/** Grants GetMySeat realm roles in the identity provider. The new role shows up in the user's next token. */
public interface RoleGrants {

	/**
	 * Grants {@code role} to the user with Keycloak subject {@code subject}. Idempotent: granting a role the user
	 * already has is a no-op, so a failed call can safely be retried.
	 * @throws RoleGrantException if the identity provider can't be reached or refuses the grant
	 */
	void grant(UUID subject, Role role);

}
