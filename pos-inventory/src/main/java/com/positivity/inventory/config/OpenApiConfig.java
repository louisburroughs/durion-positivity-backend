package com.positivity.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
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
                                                .title("Positivity Inventory API")
                                                .description("API documentation for the Inventory service, accessible via the API Gateway.")
                                                .version("v1"))
                                .servers(List.of(
                                                new Server().url("http://api-gateway.local/api/inventory")
                                                                .description("API Gateway")));
        }
}
