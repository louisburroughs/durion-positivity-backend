package com.positivity.order.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Order Service Security Configuration.
 *
 * <p>
 * Imports {@link GatewaySecurityConfig} which provides:
 * </p>
 * <ul>
 * <li>Gateway-based authentication via X-Authorities and X-User headers</li>
 * <li>Stateless session management (JWT-based)</li>
 * <li>Method-level security (@PreAuthorize support)</li>
 * <li>Public access to actuator, swagger endpoints</li>
 * </ul>
 *
 * <h2>⚠️ Security Assumption</h2>
 * <p>
 * This configuration trusts headers injected by pos-api-gateway.
 * It assumes services are NOT directly exposed to external networks.
 * Network isolation must be enforced at infrastructure level.
 * </p>
 *
 * @see GatewaySecurityConfig
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {
    // Gateway-based authentication is configured by GatewaySecurityConfig

    /**
     * RestClient.Builder bean for components that create service-specific clients
     * (direct-URL callers: startup registration and the pos-tax exception).
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Load-balanced builder resolving Eureka service ids for sibling-service clients
     * (internal client guide, #641).
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * RestClient bean for HTTP client support.
     */
    @Bean
    public RestClient restClient(RestClient.Builder restClientBuilder) {
        return restClientBuilder.build();
    }
}
