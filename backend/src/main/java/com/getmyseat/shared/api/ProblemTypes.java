package com.getmyseat.shared.api;

import java.net.URI;

/** Stable {@code type} URIs for the problems the API returns. Clients may rely on these never changing. */
public final class ProblemTypes {

	public static final URI VALIDATION = of("validation");

	public static final URI MALFORMED_REQUEST = of("malformed-request");

	public static final URI UNAUTHORIZED = of("unauthorized");

	public static final URI FORBIDDEN = of("forbidden");

	public static final URI NOT_FOUND = of("not-found");

	public static final URI METHOD_NOT_ALLOWED = of("method-not-allowed");

	public static final URI CONFLICT = of("conflict");

	/** Some of the Seats or General Admission places asked for are gone; nothing was held. */
	public static final URI INVENTORY_UNAVAILABLE = of("inventory-unavailable");

	public static final URI UPSTREAM_UNAVAILABLE = of("upstream-unavailable");

	public static final URI INTERNAL_ERROR = of("internal-error");

	private ProblemTypes() {
	}

	private static URI of(String name) {
		return URI.create("urn:getmyseat:problem:" + name);
	}

}
