package com.positivity.documents.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Positivity Documents API")
                        .version("v1")
                        .description("API documentation for document rendering and format conversion")
                        .contact(new Contact()
                                .email("louis.burroughs@gmail.com")
                                .name("Durion Team")));
    }
}
