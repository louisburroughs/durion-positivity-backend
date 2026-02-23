package com.positivity.location.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Imports gateway-driven security configuration for the location service.
 * JWT validation and user/authority extraction is handled by the API gateway
 * via {@code X-User} and {@code X-Authorities} headers.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {
}
