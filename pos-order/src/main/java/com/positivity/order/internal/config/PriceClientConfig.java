package com.positivity.order.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-price (ADR-0044 utility module — synchronous REST permitted). Resolves the
 * {@code price} Eureka service id through the load-balanced builder (#641); mirrors the
 * pos-workorder {@code DocumentClientConfig} pattern.
 */
@Configuration
public class PriceClientConfig {

    @Bean
    public RestClient priceServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.price.service-id:price}") String serviceId) {
        return restClientBuilder.baseUrl("http://" + serviceId).build();
    }
}
