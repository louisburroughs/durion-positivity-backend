package com.positivity.location.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

        @Bean
        public OpenAPI customOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity Location API")
                                                .description("API documentation for the Shop Location service, accessible via the API Gateway. Shop locations are Maintenance Bays and Mobile Shops.")
                                                .version("v1")
                                                .contact(new Contact()
                                                                .email("louis.burroughs@gmail.com")
                                                                .name("Durion Team")))
                                .servers(List.of(
                                                new Server().url("http://api-gateway.local/api/location")
                                                                .description("API Gateway")));
        }
}
