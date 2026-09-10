package com.positivity.tenant.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Gateway-driven security: JWT validation and authority extraction happen at the API gateway,
 * which forwards {@code X-User} / {@code X-Authorities}. The {@code platform:*} authorities this
 * module checks exist only in the platform tenant's role template (ADR-0062 §7).
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
