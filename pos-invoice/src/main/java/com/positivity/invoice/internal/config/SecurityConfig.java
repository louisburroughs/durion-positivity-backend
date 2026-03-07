package com.positivity.invoice.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Invoice service security configuration.
 *
 * <p>Imports shared gateway security so invoice uses the standard auth model,
 * including public OpenAPI and actuator endpoints.</p>
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {
}
