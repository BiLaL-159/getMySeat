package com.getmyseat.shared.api;

/**
 * The resource doesn't exist, or the caller isn't allowed to know it exists. Returned as {@code 404}.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
