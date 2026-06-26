package com.positivity.tax.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Tax Service Security Configuration.
 *
 * <p>
 * pos-tax is an internal-only service. It is never exposed through the API gateway (see
 * ADR-0014) and is reached directly over the internal Docker network by sibling services
 * such as pos-invoice, which call it without a bearer token. All endpoints are therefore
 * permitted at the HTTP layer.
 * </p>
 *
 * <p>
 * Method-level security is intentionally NOT enabled here, so any residual
 * {@code @PreAuthorize} annotations on controllers are not evaluated and do not block these
 * internal, unauthenticated service-to-service calls.
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @SuppressWarnings("java:S4502") // CSRF not needed: stateless internal API, no cookies
    public SecurityFilterChain taxSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
