package com.positivity.warranty.internal.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * RestClient builders for pos-warranty outbound integrations (PRD §9.4).
 *
 * <p>The {@code @Primary} plain builder serves direct-URL callers (event-type and
 * permission registration against fixed base URLs); the {@code @LoadBalanced}
 * builder resolves Eureka service ids for the sibling-service clients in
 * {@code internal/client}.
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
