package com.positivity.people.config;

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
                        .title("POS Human Resources Service API")
                        .description(
                                "Human Resources service for employee management, payroll, and benefits administration")
                        .version("v1")
                        .contact(new Contact()
                                .email("louis.burroughs@gmail.com")
                                .name("Durion Team")));
    }
}
