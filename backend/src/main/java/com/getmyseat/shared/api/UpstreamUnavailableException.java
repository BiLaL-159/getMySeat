package com.getmyseat.shared.api;

/**
 * A system the request depends on (such as Keycloak) failed, so nothing was changed and retrying is safe.
 * Returned as {@code 503}.
 */
public class UpstreamUnavailableException extends RuntimeException {

	public UpstreamUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
