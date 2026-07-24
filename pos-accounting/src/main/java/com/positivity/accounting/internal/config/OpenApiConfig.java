package com.positivity.accounting.internal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the POS Accounting Service OpenAPI (Swagger) documentation.
 *
 * <p>Registers the top-level {@link OpenAPI} bean that describes the service metadata
 * (title, version, contact) and the {@code bearerAuth} JWT security scheme used by all
 * protected endpoints.
 *
 * <p>Also registers an {@link OperationCustomizer} that inspects each handler method for
 * {@link org.springframework.security.access.prepost.PreAuthorize} annotations and surfaces
 * the required authority strings as the {@code x-required-permissions} OpenAPI extension,
 * making security requirements visible in the generated API documentation.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Produces the {@link OpenAPI} descriptor bean with service metadata and the
     * {@code bearerAuth} HTTP Bearer / JWT security scheme.
     *
     * @return a fully configured {@link OpenAPI} instance
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("POS Accounting Service API")
                        .description("Accounting service for invoice, journal entry, and GL account management")
                        .version("v1")
                        .contact(
                                new Contact().email("louis.burroughs@gmail.com").name("Durion Support Services")))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
