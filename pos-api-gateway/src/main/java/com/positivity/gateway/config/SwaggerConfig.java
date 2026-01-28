package com.positivity.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

        @Bean
        public OpenAPI aggregatedOpenAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity API Gateway")
                                                .description("Unified API documentation for all POS modules.")
                                                .version("1.0.0"))
                                .servers(List.of(
                                                new Server().url("http://api-gateway.local/catalog")
                                                                .description("Catalog module"),
                                                new Server().url("http://api-gateway.local/customer")
                                                                .description("Customer module"),
                                                new Server().url("http://api-gateway.local/inventory")
                                                                .description("Inventory module"),
                                                new Server().url("http://api-gateway.local/image")
                                                                .description("Image module"),
                                                new Server().url("http://api-gateway.local/location")
                                                                .description("Location module"),
                                                new Server().url("http://api-gateway.local/people")
                                                                .description("People module"),
                                                new Server().url("http://api-gateway.local/security-service")
                                                                .description("Security module"),
                                                new Server().url("http://api-gateway.local/shop-manager")
                                                                .description("Shop manager module"),
                                                new Server().url("http://api-gateway.local/vehicle-fitment")
                                                                .description("Vehicle fitment module"),
                                                new Server().url("http://api-gateway.local/vehicle-inventory")
                                                                .description("Vehicle inventory module"),
                                                new Server().url("http://api-gateway.local/work-order")
                                                                .description("Workorder module"),
                                                new Server().url("http://api-gateway.local/vehicle-reference-carapi")
                                                                .description("Vehicle reference CarAPI module"),
                                                new Server().url("http://api-gateway.local/vehicle-reference-nhtsa")
                                                                .description("Vehicle reference NHTSA module")));
        }
}
