package com.positivity.tax.internal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
import org.springdoc.core.customizers.OpenApiCustomizer;
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
                        .contact(new Contact().name("Durion Support Services").email("platform@durionpos.org")))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    /**
     * Declares the plausibility check's {@code receiptTotal > 0} as OpenAPI 3.1 {@code exclusiveMinimum: 0}
     * (CAP:550 S32b). springdoc renders neither {@code @DecimalMin(inclusive = false)} nor
     * {@code @Schema(exclusiveMinimum = true)} as an exclusive bound on a 3.1 model property, so the published
     * contract would otherwise advertise 0 as valid while the endpoint refuses it.
     *
     * @return the customizer
     */
    @Bean
    public OpenApiCustomizer plausibilityReceiptTotalExclusiveMinimum() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Schema<?> request = openApi.getComponents().getSchemas().get("TaxPlausibilityCheckRequest");
            if (request == null || request.getProperties() == null) {
                return;
            }
            Schema<?> receiptTotal = request.getProperties().get("receiptTotal");
            if (receiptTotal != null) {
                receiptTotal.setMinimum(null);
                receiptTotal.setExclusiveMinimum(null);
                receiptTotal.setExclusiveMinimumValue(BigDecimal.ZERO);
            }
        };
    }
}
