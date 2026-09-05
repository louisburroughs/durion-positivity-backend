package com.positivity.securityservice.internal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata and springdoc customizers for pos-security-service.
 *
 * <p>Registers {@link ProducibleResponsesOperationCustomizer} alongside pos-security-common's
 * {@code x-required-permissions} customizer (see {@code RequiredPermissionsOpenApiAutoConfiguration}). Both
 * customizers run — pos-security-common's auto-configured bean is no longer conditional on the absence of other
 * {@link OperationCustomizer} beans (issue #1721) — so this service can prune the generic
 * {@code 400}/{@code 401}/{@code 403}/{@code 404}/{@code 409} responses that {@code GlobalExceptionHandler}'s
 * unscoped {@code @ControllerAdvice} otherwise merges onto every operation, without disabling the
 * required-permissions extension.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("POS Security Service API")
                        .version("v1")
                        .description("API documentation for POS Security Service")
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

    @Bean
    public OperationCustomizer producibleResponsesOperationCustomizer() {
        return new ProducibleResponsesOperationCustomizer();
    }
}
