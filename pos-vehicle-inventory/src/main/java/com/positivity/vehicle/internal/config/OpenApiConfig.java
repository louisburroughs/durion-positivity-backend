package com.positivity.vehicle.internal.config;

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
                        .title("Vehicle Inventory API")
                        .version("1.0")
                        .description("API for managing vehicle inventory in the POS system")
                        .contact(new Contact().name("Durion Support Services").email("platform@durionpos.org")));
    }
}
