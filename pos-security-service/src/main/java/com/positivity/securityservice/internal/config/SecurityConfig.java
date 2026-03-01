package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.security.CustomUserDetailsService;
import com.positivity.securityservice.internal.security.GatewayHeaderAuthenticationFilter;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private static final String V1_USERS_LOGIN = "/v1/users/login";
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomUserDetailsService userDetailsService;

    @Bean
    public GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter() {
        return new GatewayHeaderAuthenticationFilter();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http,
            GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter) {
        try {
            http
                    .securityMatcher("/v1/auth/**", V1_USERS_LOGIN)
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/v1/auth/**", V1_USERS_LOGIN))
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/v1/auth/login", "/v1/auth/token-pair",
                                    "/v1/auth/refresh", "/v1/auth/validate", V1_USERS_LOGIN)
                            .permitAll()
                            .anyRequest().authenticated())
                    .userDetailsService(userDetailsService)
                    .addFilterBefore(gatewayHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build auth security filter chain", e);
        }
    }

    @Bean
    @Order(2)
    public SecurityFilterChain gatewaySecurityFilterChain(
            HttpSecurity http,
            GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter) {
        try {
            http
                    .csrf(csrf -> csrf.ignoringRequestMatchers(
                            "/v1/**",
                            "/actuator/**",
                            "/v3/api-docs",
                            "/v3/api-docs/**",
                            "/v3/api-docs.yaml",
                            "/swagger-ui/**",
                            "/swagger-ui.html"))
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                    "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll()
                            .requestMatchers("/actuator/health").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(gatewayHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build gateway security filter chain", e);
        }
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        try {
            return configuration.getAuthenticationManager();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize authentication manager", e);
        }
    }
}
