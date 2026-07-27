package com.positivity.order.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-price (ADR-0044 utility module — synchronous REST permitted). Mirrors the
 * pos-workorder {@code TaxClientConfig} pattern.
 */
@Configuration
public class PriceClientConfig {

    @Bean
    public RestClient priceServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.price.base-url:http://pos-price:8080}") String priceServiceBaseUrl) {
        return restClientBuilder.baseUrl(priceServiceBaseUrl).build();
    }
}
