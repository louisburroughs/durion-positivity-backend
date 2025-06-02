package com.positivity.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Positivity Catalog API")
                        .description("API documentation for the Catalog service, accessible via the API Gateway.")
                        .version("1.0.0"))
                .servers(List.of(
                        new Server().url("http://api-gateway.local/api/catalog").description("API Gateway")
                ));
    }
}
