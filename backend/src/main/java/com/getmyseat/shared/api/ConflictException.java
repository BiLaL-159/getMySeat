package com.getmyseat.shared.api;

/** The request clashes with the resource's current state. Returned as {@code 409}. */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}

}
