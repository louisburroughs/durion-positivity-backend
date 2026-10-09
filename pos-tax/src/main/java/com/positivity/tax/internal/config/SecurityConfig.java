package com.positivity.tax.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.tax.internal.security.FrontDoorSecretFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Tax Service Security Configuration.
 *
 * <p>Imports {@link GatewaySecurityConfig} which provides gateway-based authentication, stateless session
 * management, and public access to actuator and OpenAPI endpoints.
 *
 * <p>The tax-registration writes (CAP:550 S32c; ADR-0071 §6) are not the gateway's: pos-accounting, their only
 * front door, calls them directly with its per-caller secret. {@code /v1/tax/registrations/**} gets its own chain,
 * ordered ahead of the imported gateway one, in which {@link FrontDoorSecretFilter} is the only authentication;
 * gateway {@code X-Authorities} headers carry no weight on it. The filter is built here rather than as a bean so
 * Boot does not also register it as a plain servlet filter outside the chain.
 */
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    @Bean
    @Order(0)
    @SuppressWarnings("java:S4502") // CSRF not needed: stateless service-to-service call, secret in a header
    public SecurityFilterChain taxRegistrationFrontDoorChain(
            HttpSecurity http, TaxProperties properties, Clock clock, ObjectMapper objectMapper) {
        http.securityMatcher(FrontDoorSecretFilter.PATH_PREFIX + "/**", FrontDoorSecretFilter.PATH_PREFIX)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(
                        handler -> handler.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new FrontDoorSecretFilter(
                                properties.getFrontDoors().getAccountingSecret(), clock, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
