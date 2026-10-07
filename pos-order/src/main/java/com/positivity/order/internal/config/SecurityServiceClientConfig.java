package com.positivity.order.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-security-service's internal step-up check (CAP:550 S16, #2512; AW31).
 * pos-security-service is an ADR-0044 utility module; the call resolves its Eureka service id through
 * the load-balanced builder and never leaves the mesh.
 */
@Configuration
public class SecurityServiceClientConfig {

    @Bean
    public RestClient securityServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.security-service.service-id:security-service}") String serviceId) {
        return restClientBuilder.baseUrl("http://" + serviceId).build();
    }
}
