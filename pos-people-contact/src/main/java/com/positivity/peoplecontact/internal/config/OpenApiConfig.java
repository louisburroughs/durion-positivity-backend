package com.positivity.peoplecontact.internal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("POS People Contact Service API")
                        .description("Identity, contact, and user-link authority service (ADR-0044 Phase 3)")
                        .version("v1")
                        .contact(
                                new Contact().email("louis.burroughs@gmail.com").name("Durion Support Services")))
                // Tags come from each controller's @Tag annotation; declaring them here too
                // duplicates entries in the exported spec (OpenAPI validators reject that).
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
