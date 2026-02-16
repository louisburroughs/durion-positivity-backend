package com.positivity.security.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Base security configuration for services using gateway-based authentication.
 *
 * <h2>Usage</h2>
 * <p>
 * Services should import this configuration:
 * </p>
 * 
 * <pre>
 * {
 *         &#64;code
 *         &#64;Configuration
 *         @Import(GatewaySecurityConfig.class)
 *         public class MyServiceSecurityConfig {
 *                 // Additional service-specific configuration
 *         }
 * }
 * </pre>
 *
 * <h2>What This Provides</h2>
 * <ul>
 * <li>Stateless session management (no server-side sessions)</li>
 * <li>CSRF disabled (stateless API, protected by JWT)</li>
 * <li>Gateway authorities filter for reading X-Authorities header</li>
 * <li>Method-level security enabled (@PreAuthorize, @Secured)</li>
 * <li>Public access to actuator, swagger, and OpenAPI endpoints</li>
 * <li>All other endpoints require authentication</li>
 * </ul>
 *
 * @see GatewayAuthoritiesFilter
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class GatewaySecurityConfig {

        /**
         * Creates the gateway authorities filter bean.
         * <p>
         * This is defined as a bean rather than using @Component to ensure it is
         * available when this configuration is imported via @Import annotation.
         * Component scanning does not work across module boundaries with @Import.
         * </p>
         *
         * @return the gateway authorities filter
         */
        @Bean
        public GatewayAuthoritiesFilter gatewayAuthoritiesFilter() {
                return new GatewayAuthoritiesFilter();
        }

        @Bean
        @Order(1)
        public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http,
                        GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
                http
                                // Disable CSRF - stateless API protected by JWT at gateway
                                .csrf(csrf -> csrf.disable())

                                // Stateless session - no server-side session storage
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // Authorization rules - using String patterns (Spring Security 7.0+)
                                .authorizeHttpRequests(auth -> auth
                                                // Public endpoints - health checks, metrics, API docs
                                                .requestMatchers("/actuator/**").permitAll()
                                                .requestMatchers("/swagger-ui/**").permitAll()
                                                .requestMatchers("/swagger-ui.html").permitAll()
                                                .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                                                .requestMatchers("/api-docs/**").permitAll()
                                                .requestMatchers("/webjars/**").permitAll()
                                                .requestMatchers("/error", "/error/**").permitAll()

                                                // Permission registration endpoint - internal services only
                                                // Note: This is called at startup before authentication is fully set up
                                                .requestMatchers("/v1/permissions/register").permitAll()

                                                // All other requests require authentication
                                                .anyRequest().authenticated())

                                // Add gateway authorities filter before username/password filter
                                .addFilterBefore(gatewayAuthoritiesFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }
}
