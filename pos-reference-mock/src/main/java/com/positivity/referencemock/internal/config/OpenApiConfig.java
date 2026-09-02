package com.positivity.referencemock.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the mock provider. Unlike platform services there is no bearerAuth
 * security scheme: the mock simulates an external vendor outside the platform mesh, and the
 * generated spec is the normative description of the Durion-normalized labor-guide provider
 * contract (service-time-sourcing-plan §10) that Phase-2 vendor adapters must translate onto.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Durion Labor-Guide Provider Contract (Mock Vendor)")
                        .version("1.0")
                        .description("Fake external labor-guide vendor serving the Durion-normalized provider"
                                + " contract from checked-in deterministic fixtures"
                                + " (pos-catalog/docs/service-time-sourcing-plan.md §10, issue #1569 Phase 1)."
                                + " This spec is the normative provider contract: Phase-2 aggregator/OEM adapters"
                                + " translate vendor reality onto exactly these shapes. Not a platform API — no"
                                + " Eureka, no gateway route, no authentication.")
                        .contact(new Contact().name("Durion Support Services").email("platform@durionpos.org")));
    }
}
