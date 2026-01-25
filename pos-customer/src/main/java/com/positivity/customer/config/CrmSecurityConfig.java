package com.positivity.customer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * CRM Service Security Configuration
 *
 * Enables method-level security (via @PreAuthorize) for permission-based access
 * control.
 * All CRM endpoints are protected and require appropriate permissions from the
 * Security Domain.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class CrmSecurityConfig {

    /**
     * Configure HTTP security for CRM service
     * - Stateless session (JWT-based)
     * - Public endpoints: Swagger, health checks, service discovery
     * - Protected endpoints: All /v1/crm/* paths require authentication
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                        .requestMatchers("/eureka/**").permitAll()

                        // All CRM endpoints require authentication
                        // Permission checks are enforced via @PreAuthorize at method level
                        .requestMatchers("/v1/crm/**").authenticated()

                        // All other requests require authentication
                        .anyRequest().authenticated());

        return http.build();
    }

    /**
     * Password encoder for any user/password operations
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
