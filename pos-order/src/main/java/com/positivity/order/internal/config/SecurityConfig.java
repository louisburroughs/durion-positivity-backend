package com.positivity.order.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
     * RestClient.Builder bean for components that create service-specific clients.
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
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
