package com.positivity.order.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * REST clients for pos-order's synchronous sibling-service calls (plan stories H1/I2). Base URLs
 * default to the Docker Compose service names; override per environment via properties.
 */
@Configuration
public class OrderClientsConfig {

    @Bean
    public RestClient inventoryServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.base-url:http://pos-inventory:8080}") String inventoryBaseUrl) {
        return restClientBuilder.baseUrl(inventoryBaseUrl).build();
    }

    @Bean
    public RestClient customerServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.customer.base-url:http://pos-customer:8080}") String customerBaseUrl) {
        return restClientBuilder.baseUrl(customerBaseUrl).build();
    }
}
