package com.positivity.catalog.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Catalog Service Security Configuration.
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
 * <h2>Previous Implementation</h2>
 * <p>
 * The previous implementation included a JwtTokenFilter that called
 * pos-security-service
 * directly to validate tokens. This has been removed because:
 * </p>
 * <ul>
 * <li>Token validation is now done at the API gateway layer</li>
 * <li>Direct service-to-service JWT validation creates circular
 * dependencies</li>
 * <li>Gateway header injection is more efficient (single validation per
 * request)</li>
 * </ul>
 *
 * @see GatewaySecurityConfig
 */
@Slf4j
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

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
