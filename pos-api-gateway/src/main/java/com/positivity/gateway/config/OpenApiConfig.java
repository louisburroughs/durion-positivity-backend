package com.positivity.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * OpenAPI configuration for the Positivity API Gateway.
 *
 * All services are accessed through the gateway at http://localhost:8080.
 * API versioning is done via the X-API-Version header (not in the path).
 *
 * Example request:
 * curl -H "X-API-Version: 1" http://localhost:8080/inventory/items
 *
 * The gateway dynamically routes requests to microservices using Eureka service
 * discovery.
 * No additional gateway configuration is needed when new services are added.
 */
@Configuration
public class OpenApiConfig {

        @Value("${gateway.host:http://localhost:8080}")
        private String gatewayHost;

        @Bean
        public OpenAPI aggregatedOpenAPI() {
                // X-API-Version is consumed by gateway filters, not by controller operations.
                // The x-purpose extension documents intent and suppresses "unused component"
                // warnings.
                Parameter apiVersionParam = new Parameter()
                                .in("header")
                                .name("X-API-Version")
                                .description("API version number (required)")
                                .required(true)
                                .schema(new io.swagger.v3.oas.models.media.StringSchema()
                                                ._default("1")
                                                .example("1"));
                apiVersionParam.addExtension("x-purpose",
                                "Documented for gateway header routing; no local $ref expected");

                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity API Gateway")
                                                .description("Unified API Gateway for all POS microservices.\n\n"
                                                                + "**API Versioning**: All requests must include the `X-API-Version` header.\n\n"
                                                                + "**Available Routes**:\n"
                                                                + "- `/accounting/**` - General ledger, journal entries, financial operations\n"
                                                                + "- `/catalog/**` - Product information, categorization, details\n"
                                                                + "- `/customer/**` - Customer profiles, preferences, history\n"
                                                                + "- `/event-receiver/**` - Event ingestion and processing\n"
                                                                + "- `/image/**` - Image storage, retrieval, transformation\n"
                                                                + "- `/inquiry/**` - Customer inquiries and support tracking\n"
                                                                + "- `/inventory/**` - Stock management and availability\n"
                                                                + "- `/invoice/**` - Invoice generation, tracking, management\n"
                                                                + "- `/location/**` - Store locations, warehouses, branches\n"
                                                                + "- `/order/**` - Order creation, fulfillment, tracking\n"
                                                                + "- `/people/**` - Employee management, staff administration\n"
                                                                + "- `/price/**` - Pricing rules, discounts, cost calculations\n"
                                                                + "- `/security-service/**` - Authentication, authorization, access control\n"
                                                                + "- `/shop-manager/**` - Shop operations, scheduling, resources\n"
                                                                + "- `/vehicle-fitment/**` - Vehicle compatibility, parts installation\n"
                                                                + "- `/vehicle-inventory/**` - Vehicle stock and availability\n"
                                                                + "- `/workorder/**` - Service requests, job tracking, assignments\n\n"
                                                                + "**Example**: `curl -H \"X-API-Version: 1\" "
                                                                + gatewayHost + "/inventory/items`")
                                                .version("1.0.0")
                                                .contact(new Contact()
                                                                .email("louis.burroughs@gmail.com")
                                                                .name("Durion Team")))
                                .servers(List.of(
                                                new Server().url(gatewayHost)
                                                                .description("API Gateway - All services routed through this endpoint")))
                                .components(new Components()
                                                .addParameters("X-API-Version", apiVersionParam));
        }
}
