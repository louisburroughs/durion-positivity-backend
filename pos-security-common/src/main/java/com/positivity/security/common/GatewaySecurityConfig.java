package com.positivity.security.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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
 * <li>Public access only to actuator health endpoints used by container health checks</li>
 * <li>Basic-auth protected access to actuator Prometheus metrics endpoint</li>
 * <li>No access to other actuator endpoints by default</li>
 * <li>Public access to swagger and OpenAPI endpoints</li>
 * <li>All other endpoints require authentication</li>
 * </ul>
 *
 * <p>
 * Production guidance: pair this with service configuration
 * {@code management.endpoint.health.show-details=never} (or
 * {@code when-authorized}) to avoid leaking internals from public health
 * responses.
 * </p>
 *
 * @see GatewayAuthoritiesFilter
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class GatewaySecurityConfig {
        private static final GrantedAuthority ACTUATOR_METRICS_ROLE = new SimpleGrantedAuthority(
                        "ROLE_ACTUATOR_METRICS");

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
        @SuppressWarnings("java:S4502") // CSRF not needed: stateless API, JWT in headers (not cookies)
        public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http,
                        GatewayAuthoritiesFilter gatewayAuthoritiesFilter,
                        @Value("${pos.security.metrics-scrape.username:prometheus}") String metricsUsername,
                        @Value("${pos.security.metrics-scrape.password:prometheus-scrape}") String metricsPassword) {
                http
                                // Disable CSRF - stateless API protected by JWT at gateway
                                // Safe because: no cookies, JWT tokens require explicit headers, SOP prevents
                                // CSRF
                                .csrf(csrf -> csrf.disable())

                                // Stateless session - no server-side session storage
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // Authorization rules - using String patterns (Spring Security 7.0+)
                                .authorizeHttpRequests(auth -> auth
                                                // Public endpoints - health checks and API docs
                                                .requestMatchers("/actuator/health", "/actuator/health/**")
                                                .permitAll()
                                                // Prometheus scraping uses dedicated machine credentials
                                                .requestMatchers("/actuator/prometheus")
                                                .hasRole("ACTUATOR_METRICS")
                                                // Deny all other actuator endpoints by default
                                                .requestMatchers("/actuator/**").denyAll()
                                                .requestMatchers("/swagger-ui/**").permitAll()
                                                .requestMatchers("/swagger-ui.html").permitAll()
                                                .requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                                                "/v3/api-docs.yaml").permitAll()
                                                .requestMatchers("/api-docs/**").permitAll()
                                                .requestMatchers("/webjars/**").permitAll()
                                                .requestMatchers("/error", "/error/**").permitAll()

                                                // Permission registration endpoint - internal services only
                                                // Note: This is called at startup before authentication is fully set up
                                                .requestMatchers("/v1/permissions/register").permitAll()

                                                // All other requests require authentication
                                                .anyRequest().authenticated())

                                .exceptionHandling(handler -> handler
                                                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                                .httpBasic(Customizer.withDefaults())
                                .authenticationProvider(
                                                prometheusScrapeAuthenticationProvider(metricsUsername, metricsPassword))

                                // Add gateway authorities filter before username/password filter
                                .addFilterBefore(gatewayAuthoritiesFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        private AuthenticationProvider prometheusScrapeAuthenticationProvider(
                        String metricsUsername,
                        String metricsPassword) {
                return new AuthenticationProvider() {
                        @Override
                        public Authentication authenticate(Authentication authentication) {
                                String username = authentication.getName();
                                String password = authentication.getCredentials() == null
                                                ? ""
                                                : authentication.getCredentials().toString();
                                if (!constantTimeEquals(username, metricsUsername)
                                                || !constantTimeEquals(password, metricsPassword)) {
                                        throw new BadCredentialsException("Invalid Prometheus scrape credentials");
                                }
                                return UsernamePasswordAuthenticationToken.authenticated(
                                                username,
                                                null,
                                                List.of(ACTUATOR_METRICS_ROLE));
                        }

                        @Override
                        public boolean supports(Class<?> authentication) {
                                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
                        }
                };
        }

        private boolean constantTimeEquals(String left, String right) {
                byte[] leftBytes = left == null ? new byte[0] : left.getBytes(StandardCharsets.UTF_8);
                byte[] rightBytes = right == null ? new byte[0] : right.getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(leftBytes, rightBytes);
        }
}
