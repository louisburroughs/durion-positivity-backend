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
                        new Server().url("http://api-gateway.local/api/catalog").description("Catalog Module"),
                        new Server().url("http://api-gateway.local/api/security").description("Security Module"),
                        new Server().url("http://api-gateway.local/api/orders").description("Orders Module")
                ));
    }
}
