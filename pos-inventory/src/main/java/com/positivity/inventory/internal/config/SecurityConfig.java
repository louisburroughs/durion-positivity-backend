package com.positivity.inventory.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Inventory service security configuration.
 *
 * <p>Imports shared gateway security so inventory uses the same auth model
 * as other backend services, including public OpenAPI and actuator endpoints.</p>
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
