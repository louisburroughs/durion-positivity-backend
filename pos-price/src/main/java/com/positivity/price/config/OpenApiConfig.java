package com.positivity.price.config;

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
                                                .title("Positivity Price API")
                                                .description(
                                                                "Price service for managing product pricing and discounts")
                                                .version("v1")
                                                .contact(new Contact()
                                                                .email("louis.burroughs@gmail.com")
                                                                .name("Durion Team")));
        }
}
