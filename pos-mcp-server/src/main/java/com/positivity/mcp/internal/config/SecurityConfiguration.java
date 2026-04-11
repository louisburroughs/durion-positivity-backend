package com.positivity.mcp.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfiguration {
    // Use shared gateway security (GatewayAuthoritiesFilter + stateless rules).

    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "mcp.security", name = "permit-all-transport", havingValue = "true")
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain localMcpTransportSecurityFilterChain(HttpSecurity http, McpServerProperties properties)
            throws Exception {
        http
                .securityMatcher(properties.sseEndpoint(), properties.messageEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
