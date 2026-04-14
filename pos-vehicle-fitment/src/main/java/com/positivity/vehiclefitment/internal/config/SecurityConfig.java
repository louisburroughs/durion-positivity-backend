package com.positivity.vehiclefitment.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Vehicle Fitment service security configuration.
 * <p>
 * Reuses standard gateway-based stateless security from
 * {@link GatewaySecurityConfig}.
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
