package com.positivity.tax.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Tax Service Security Configuration.
 *
 * <p>
 * Imports {@link GatewaySecurityConfig} which provides gateway-based authentication,
 * stateless session management, and public access to actuator and OpenAPI endpoints.
 * </p>
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {
}
