package com.positivity.warranty.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Warranty service security configuration.
 * <p>
 * Reuses standard gateway-based stateless security from
 * {@link GatewaySecurityConfig}: the API gateway validates JWTs and injects
 * X-Authorities / X-User headers which the imported filter chain trusts.
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
