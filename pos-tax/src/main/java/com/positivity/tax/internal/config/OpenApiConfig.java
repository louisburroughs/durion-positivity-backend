package com.positivity.tax.internal.config;

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
                        .title("POS Tax Service API")
                        .version("1.0")
                        .description("Tax calculation service with external API passthrough and test mode support")
                        .contact(new Contact()
                                .name("Durion Team")
                                .email("platform@durionpos.org")));
    }
}
