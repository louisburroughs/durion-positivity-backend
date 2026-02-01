package com.positivity.workorder.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Workorder Service Security Configuration.
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
 * @see GatewaySecurityConfig
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    /**
     * RestClient bean for HTTP client support.
     */
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
