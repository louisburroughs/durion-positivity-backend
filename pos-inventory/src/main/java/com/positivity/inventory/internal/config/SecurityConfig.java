package com.positivity.inventory.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Inventory service security configuration.
 *
 * <p>Imports shared gateway security so inventory uses the same auth model
 * as other backend services, including public OpenAPI and actuator endpoints.</p>
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

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
