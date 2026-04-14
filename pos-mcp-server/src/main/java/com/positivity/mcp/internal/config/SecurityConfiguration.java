package com.positivity.mcp.internal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.security.common.GatewayAuthoritiesFilter;
import com.positivity.security.common.GatewaySecurityConfig;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfiguration {
    // Use shared gateway security (GatewayAuthoritiesFilter + stateless rules).

    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "mcp.security", name = "permit-all-transport", havingValue = "true")
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain localMcpTransportSecurityFilterChain(HttpSecurity http, McpServerProperties properties) {
        http.securityMatcher(properties.sseEndpoint(), properties.messageEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    ApiErrorAuthenticationEntryPoint apiErrorAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        return new ApiErrorAuthenticationEntryPoint(objectMapper, clock);
    }

    /**
     * Dedicated security filter chain for the MCP REST API.
     * Uses {@link ApiErrorAuthenticationEntryPoint} so unauthenticated requests
     * return an {@link com.positivity.shared.error.ApiError} JSON body (ADR-0017),
     * not the bare 401 produced by the shared gateway's
     * {@code HttpStatusEntryPoint}.
     */
    @Bean
    @Order(-1)
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain mcpApiSecurityFilterChain(
            HttpSecurity http,
            GatewayAuthoritiesFilter gatewayAuthoritiesFilter,
            ApiErrorAuthenticationEntryPoint apiErrorEntryPoint) {
        http.securityMatcher("/v1/mcp/**", "/v1/nlt/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(handler -> handler.authenticationEntryPoint(apiErrorEntryPoint))
                .addFilterBefore(gatewayAuthoritiesFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
