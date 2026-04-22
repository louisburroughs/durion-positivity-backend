package com.positivity.workorder.internal.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String WORKORDER_API_TITLE = "POS Workorder Service API";
    private static final String WORKORDER_API_DESCRIPTION = "Workorder service for managing workorders and tasks";
    private static final String WORKORDER_API_VERSION = "v1";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(WORKORDER_API_TITLE)
                        .description(WORKORDER_API_DESCRIPTION)
                        .version(WORKORDER_API_VERSION)
                        .contact(
                                new Contact().email("louis.burroughs@gmail.com").name("Durion Support Services")));
    }

    @Bean
    public OpenApiCustomizer enforceWorkorderOpenApiInfo() {
        return openApi -> {
            if (openApi == null) {
                return;
            }
            var info = openApi.getInfo();
            if (info == null) {
                info = new Info();
                openApi.setInfo(info);
            }
            info.title(WORKORDER_API_TITLE)
                    .description(WORKORDER_API_DESCRIPTION)
                    .version(WORKORDER_API_VERSION)
                    .contact(new Contact().email("louis.burroughs@gmail.com").name("Durion Support Services"));
        };
    }
}
