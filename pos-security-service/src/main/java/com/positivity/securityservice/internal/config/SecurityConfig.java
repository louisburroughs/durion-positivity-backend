package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.security.GatewayHeaderAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
@EnableConfigurationProperties(LockoutPolicy.class)
@RequiredArgsConstructor
public class SecurityConfig {
    private static final String V1_AUTH_LOGIN = "/v1/auth/login";
    private static final String V1_AUTH_SELF_REGISTER = "/v1/auth/self-register";
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;
    private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler jsonAccessDeniedHandler;

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
                    .securityMatcher("/v1/auth/**")
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/v1/auth/**"))
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/v1/auth/token-pair",
                                    "/v1/auth/refresh", "/v1/auth/validate", V1_AUTH_LOGIN, V1_AUTH_SELF_REGISTER)
                            .permitAll()
                            .anyRequest().authenticated())
                    .userDetailsService(userDetailsService)
                    .addFilterBefore(gatewayHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));

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
                            .requestMatchers(HttpMethod.GET, "/v1/permissions/catalog-version").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(gatewayHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));

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
