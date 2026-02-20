package com.positivity.price.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Price Service Security Configuration.
 *
 * <p>
 * Imports {@link GatewaySecurityConfig} which provides:
 * </p>
 * <ul>
 * <li>Gateway-based authentication via X-Authorities and X-User headers</li>
 * <li>Stateless session management (JWT-based)</li>
 * <li>Method-level security (@PreAuthorize support)</li>
 * <li>Public access to actuator, Swagger UI, and OpenAPI endpoints</li>
 * </ul>
 *
 * <h2>Security Assumption</h2>
 * <p>
 * This module trusts identity/authority headers injected by the API gateway.
 * Services must not be directly exposed to external networks.
 * </p>
 *
 * @see GatewaySecurityConfig
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    /**
     * RestClient bean for service-to-service HTTP clients.
     *
     * @return a RestClient instance
     */
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
