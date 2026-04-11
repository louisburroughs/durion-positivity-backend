package com.positivity.accounting.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Accounting Service Security Configuration.
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
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {
    // Gateway-based authentication is configured by GatewaySecurityConfig
    // No additional beans required
}
