package com.positivity.mcp.internal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.security.common.GatewayAuthoritiesFilter;
import com.positivity.security.common.GatewaySecurityConfig;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
     * Companion to {@link #apiErrorAuthenticationEntryPoint}: writes the same {@link
     * com.positivity.shared.error.ApiError} envelope for requests the filter chain itself denies
     * (403) rather than merely fails to authenticate (401) — see {@link
     * ApiErrorAccessDeniedHandler}.
     */
    @Bean
    ApiErrorAccessDeniedHandler apiErrorAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        return new ApiErrorAccessDeniedHandler(objectMapper, clock);
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
            ApiErrorAuthenticationEntryPoint apiErrorEntryPoint,
            ApiErrorAccessDeniedHandler apiErrorAccessDeniedHandler) {
        http.securityMatcher("/v1/mcp/**", "/v1/nlt/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The ASYNC dispatch is container-internal: it only happens after the original
                // REQUEST dispatch was authenticated and authorized, and a client cannot trigger it
                // directly. It must be permitted explicitly because GatewayAuthoritiesFilter (an
                // OncePerRequestFilter) does not run on async dispatches, so re-authorizing there
                // finds an empty security context — an SSE stream that completed without emitting
                // any event then surfaced as a spurious 401 from the entry point instead of its
                // real (empty 200) outcome.
                // POST /v1/mcp/transcriptions is matched ahead of the blanket .anyRequest().authenticated()
                // rule below so an authenticated caller without mcp:chat:execute is denied by this filter
                // chain (403, via apiErrorAccessDeniedHandler below — see McpTranscriptionExceptionHandler
                // for the @PreAuthorize-driven method-security equivalent, kept too as defense in depth)
                // before multipart parsing ever starts, rather than reaching
                // McpTranscriptionPreDispatchExceptionHandler's 413/415 handling for a request the caller
                // was never allowed to make.
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ASYNC)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/mcp/transcriptions")
                        .hasAuthority(McpPermissions.MCP_CHAT_EXECUTE)
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(handler -> handler.authenticationEntryPoint(apiErrorEntryPoint)
                        .accessDeniedHandler(apiErrorAccessDeniedHandler))
                .addFilterBefore(gatewayAuthoritiesFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
