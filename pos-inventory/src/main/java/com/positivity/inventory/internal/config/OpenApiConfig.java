package com.positivity.inventory.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

        @Bean
        public OpenAPI customOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity Inventory API")
                                                .description("API documentation for the Inventory service, accessible via the API Gateway.")
                                                .version("v1")
                                                .contact(
                                                                new Contact().email("louis.burroughs@gmail.com")
                                                                                .name("Durion Support Services")))
                                .tags(Arrays.asList(
                                                new Tag()
                                                                .name("Cycle Count Operations")
                                                                .description("Cycle count submission and recount operations"),
                                                new Tag()
                                                                .name("Cycle Count Query")
                                                                .description("Query endpoints for cycle count tasks and history")));
        }
}
