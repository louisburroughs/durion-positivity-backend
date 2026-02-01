package com.positivity.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway route configuration for all POS microservices.
 * Routes requests to backend services via Eureka load balancing.
 * Version is passed via X-API-Version header, not in the path.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("accounting", r -> r
                        .path("/accounting/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://ACCOUNTING"))
                .route("catalog", r -> r
                        .path("/catalog/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://CATALOG"))
                .route("customer", r -> r
                        .path("/customer/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://CUSTOMER"))
                .route("event-receiver", r -> r
                        .path("/event-receiver/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://EVENT-RECEIVER"))
                .route("image", r -> r
                        .path("/image/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://IMAGE"))
                .route("inquiry", r -> r
                        .path("/inquiry/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://INQUIRY"))
                .route("inventory", r -> r
                        .path("/inventory/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://INVENTORY"))
                .route("invoice", r -> r
                        .path("/invoice/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://INVOICE"))
                .route("location", r -> r
                        .path("/location/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://LOCATION"))
                .route("order", r -> r
                        .path("/order/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://ORDER"))
                .route("people", r -> r
                        .path("/people/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://PEOPLE"))
                .route("price", r -> r
                        .path("/price/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://PRICE"))
                .route("security-service", r -> r
                        .path("/security-service/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://SECURITY-SERVICE"))
                .route("shop-manager", r -> r
                        .path("/shop-manager/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://SHOP-MANAGER"))
                .route("vehicle-fitment", r -> r
                        .path("/vehicle-fitment/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://VEHICLE-FITMENT"))
                .route("vehicle-inventory", r -> r
                        .path("/vehicle-inventory/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://VEHICLE-INVENTORY"))
                .route("workorder", r -> r
                        .path("/workorder/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://WORKORDER"))
                .build();
    }
}
