package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class InventoryClientConfig {

    @Bean
    public RestClient inventoryServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.base-url:http://pos-inventory:8087}") String inventoryBaseUrl) {
        return restClientBuilder.baseUrl(inventoryBaseUrl).build();
    }
}
