package com.getmyseat.access;

/** The GetMySeat realm roles. Each maps to the Spring authority {@code ROLE_<name>}. */
public enum Role {

	CUSTOMER, ORGANIZER, ADMIN;

	public String authority() {
		return "ROLE_" + name();
	}

}
