package com.getmyseat.shared.api;

import java.net.URI;
import java.util.Map;

/**
 * The request clashes with the resource's current state. Returned as {@code 409}, by default with the generic
 * {@link ProblemTypes#CONFLICT conflict} type. A subclass can give a more specific type and extension members.
 */
public class ConflictException extends RuntimeException {

	private final URI type;

	private final Map<String, Object> properties;

	public ConflictException(String message) {
		this(message, ProblemTypes.CONFLICT, Map.of());
	}

	/** @param properties extension members added to the {@code ProblemDetail} */
	protected ConflictException(String message, URI type, Map<String, Object> properties) {
		super(message);
		this.type = type;
		this.properties = Map.copyOf(properties);
	}

	public URI type() {
		return this.type;
	}

	public Map<String, Object> properties() {
		return this.properties;
	}

}
