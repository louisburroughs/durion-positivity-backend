package com.positivity.tenant.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.tenant.internal.security.TenantRegistrySecretFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway-driven security: JWT validation and authority extraction happen at the API gateway,
 * which forwards {@code X-User} / {@code X-Authorities}. The {@code platform:*} authorities this
 * module checks exist only in the platform tenant's role template (ADR-0062 §7).
 *
 * <p>One path is not the gateway's: {@code /internal/v1/tenants}, which other services' {@code
 * RemoteTenantRegistry} polls with a shared secret (plan WS4-2). It gets its own chain, ordered
 * ahead of the gateway one, in which {@link TenantRegistrySecretFilter} is the only
 * authentication; the filter is built here rather than as a bean so Boot does not also register it
 * as a plain servlet filter outside the chain.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    @Bean
    @Order(0)
    @SuppressWarnings("java:S4502") // CSRF not needed: stateless service-to-service call, secret in a header
    public SecurityFilterChain internalTenantRegistryFilterChain(
            HttpSecurity http,
            @Value("${pos.tenant.registry.api-secret:}") String registrySecret,
            Clock clock,
            ObjectMapper objectMapper) {
        http.securityMatcher(TenantRegistrySecretFilter.PATH_PREFIX + "/**", TenantRegistrySecretFilter.PATH_PREFIX)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(
                        handler -> handler.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new TenantRegistrySecretFilter(registrySecret, clock, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
