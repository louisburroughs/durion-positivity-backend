package com.positivity.mcp.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfiguration {
    // Use shared gateway security (GatewayAuthoritiesFilter + stateless rules).
}
