package com.getmyseat.access;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

	record MeResponse(UUID subject, String name, @Nullable String email, Set<Role> roles) {
	}

	@GetMapping
	@Operation(summary = "Who the API thinks the caller is",
			description = "Roles come from the access token, so a newly granted role appears after the token is refreshed.")
	MeResponse me(Caller caller) {
		return new MeResponse(caller.subject(), caller.name(), caller.email(), caller.roles());
	}

}
