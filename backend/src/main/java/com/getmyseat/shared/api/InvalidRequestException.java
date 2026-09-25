package com.getmyseat.shared.api;

/**
 * One request field fails a rule that bean validation can't express. Returned as a {@code 400} validation
 * problem, the same shape as a bean validation failure.
 */
public class InvalidRequestException extends RuntimeException {

	private final String field;

	public InvalidRequestException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return this.field;
	}

}
