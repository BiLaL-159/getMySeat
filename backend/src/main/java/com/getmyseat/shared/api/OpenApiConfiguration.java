package com.getmyseat.shared.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	static final String BEARER_JWT = "bearer-jwt";

	@Bean
	OpenAPI getMySeatOpenApi() {
		return new OpenAPI()
			.info(new Info().title("GetMySeat API")
				.version("v1")
				.description("Live-event ticketing. Sign in with a Keycloak access token from the getmyseat realm."))
			.components(new Components().addSecuritySchemes(BEARER_JWT,
					new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
	}

}
