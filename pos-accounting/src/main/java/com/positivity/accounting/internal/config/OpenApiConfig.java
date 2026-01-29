package com.positivity.accounting.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("POS Accounting Service API")
                        .description("Accounting service for invoice, journal entry, and GL account management")
                        .version("v1")
                        .contact(new Contact()
                                .email("louis.burroughs@gmail.com")
                                .name("Durion Team")));
    }
}
