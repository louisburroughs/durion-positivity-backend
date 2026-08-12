package com.positivity.supplier.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Supplier service security configuration.
 *
 * <p>Reuses standard gateway-based stateless security from {@link GatewaySecurityConfig}: the
 * API gateway validates JWTs and injects X-Authorities / X-User headers which the imported
 * filter chain trusts. Method security is deny-by-default via {@code @PreAuthorize} with the
 * {@code SupplierPermissions} authorities (ADR-0040).
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
