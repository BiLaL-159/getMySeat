package com.getmyseat.access;

/** A {@link RoleGrants} call failed; the role wasn't granted. */
public class RoleGrantException extends RuntimeException {

	public enum Reason {

		/** Keycloak couldn't be reached, refused the backend's client, or failed; retrying later may work. */
		UNAVAILABLE,

		/** Keycloak has no user with that subject, so retrying won't help. */
		UNKNOWN_USER

	}

	private final Reason reason;

	public RoleGrantException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

}
