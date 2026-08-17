package com.positivity.image.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Image service security configuration (CAP-324 #1257).
 *
 * <p>Reuses the standard gateway-based stateless chain: the API gateway validates JWTs and injects
 * {@code X-Authorities} / {@code X-User}, which this filter chain trusts. Method security is
 * deny-by-default via {@code @PreAuthorize} with {@code ImagePermissions} (ADR-0040).
 *
 * <p>Added when this module gained a way to store images. The read endpoints predate it and stay
 * open — they serve images into page renders — but a write that accepts and keeps arbitrary bytes
 * from any caller that can reach the service needed a gate.
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {}
