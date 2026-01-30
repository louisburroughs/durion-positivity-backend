package com.positivity.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * OpenAPI configuration for the Positivity API Gateway.
 *
 * All services are accessed through the gateway at http://localhost:8080 with
 * API versioning.
 * The X-API-Version header is required on all requests to specify the API
 * version.
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
                return new OpenAPI()
                                .info(new Info()
                                                .title("Positivity API Gateway")
                                                .description("Unified API Gateway for all POS microservices. "
                                                                + "All requests must include the X-API-Version header to specify the API version. "
                                                                + "The gateway automatically routes requests to the appropriate microservice using service discovery.")
                                                .version("v1")
                                                .contact(new Contact()
                                                                .email("louis.burroughs@gmail.com")
                                                                .name("Durion Team")))
                                .servers(List.of(
                                                new Server().url(gatewayHost)
                                                                .description("API Gateway (all services routed through this endpoint)"),
                                                new Server().url(gatewayHost + "/v1/accounting/**")
                                                                .description("Accounting service - General ledger, journal entries, and financial operations"),
                                                new Server().url(gatewayHost + "/v1/invoice/**")
                                                                .description("Invoice service - Invoice generation, tracking, and management"),
                                                new Server().url(gatewayHost + "/v1/inquiry/**")
                                                                .description("Inquiry service - Customer inquiries and support tracking"),
                                                new Server().url(gatewayHost + "/v1/order/**")
                                                                .description("Order service - Order creation, fulfillment, and tracking"),
                                                new Server().url(gatewayHost + "/v1/customer/**")
                                                                .description("Customer service - Customer profiles, preferences, and history"),
                                                new Server().url(gatewayHost + "/v1/inventory/**")
                                                                .description("Inventory service - Stock management and availability tracking"),
                                                new Server().url(gatewayHost + "/v1/price/**")
                                                                .description("Price service - Pricing rules, discounts, and cost calculations"),
                                                new Server().url(gatewayHost + "/v1/catalog/**")
                                                                .description("Catalog service - Product information, categorization, and details"),
                                                new Server().url(gatewayHost + "/v1/location/**")
                                                                .description("Location service - Store locations, warehouses, and branch management"),
                                                new Server().url(gatewayHost + "/v1/people/**")
                                                                .description("People service - Employee management and staff administration"),
                                                new Server().url(gatewayHost + "/v1/workorder/**")
                                                                .description("Workorder service - Service requests, job tracking, and assignments"),
                                                new Server().url(gatewayHost + "/v1/shop-manager/**")
                                                                .description("Shop Manager service - Shop operations, scheduling, and resources"),
                                                new Server().url(gatewayHost + "/v1/vehicle-fitment/**")
                                                                .description("Vehicle Fitment service - Vehicle compatibility and parts installation"),
                                                new Server().url(gatewayHost + "/v1/vehicle-inventory/**")
                                                                .description("Vehicle Inventory service - Vehicle stock and availability"),
                                                new Server().url(gatewayHost + "/v1/image/**")
                                                                .description("Image service - Image storage, retrieval, and transformation"),
                                                new Server().url(gatewayHost + "/v1/security-service/**")
                                                                .description("Security service - Authentication, authorization, and access control"),
                                                new Server().url(gatewayHost + "/v1/event-receiver/**")
                                                                .description("Event Receiver service - Event ingestion and processing")));
        }
}
