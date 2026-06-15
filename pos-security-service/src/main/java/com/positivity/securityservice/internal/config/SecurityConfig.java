package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.security.GatewayHeaderAuthenticationFilter;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.security.PermissionRegistrationSecretFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
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
    private static final String METRICS_ROLE = "ROLE_ACTUATOR_METRICS";
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;
    private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler jsonAccessDeniedHandler;
    private final PermissionRegistrationSecretFilter permissionRegistrationSecretFilter;

    @Bean
    public GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter() {
        return new GatewayHeaderAuthenticationFilter();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http, GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter) {
        try {
            http.securityMatcher("/v1/auth/**")
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/v1/auth/**"))
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.requestMatchers(
                                    "/v1/auth/refresh", "/v1/auth/validate", V1_AUTH_LOGIN, V1_AUTH_SELF_REGISTER)
                            .permitAll()
                            .anyRequest()
                            .authenticated())
                    .userDetailsService(userDetailsService)
                    .addFilterBefore(permissionRegistrationSecretFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(gatewayHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));

            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build auth security filter chain", e);
        }
    }

    @Bean
    @Order(2)
    public SecurityFilterChain actuatorMetricsFilterChain(
            HttpSecurity http,
            @Value("${pos.security.metrics-scrape.username:prometheus}") String metricsUsername,
            @Value("${pos.security.metrics-scrape.password:prometheus-scrape}") String metricsPassword) {
        try {
            http.securityMatcher("/actuator/prometheus")
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ACTUATOR_METRICS"))
                    .httpBasic(Customizer.withDefaults())
                    .authenticationProvider(prometheusScrapeAuthenticationProvider(metricsUsername, metricsPassword))
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));
            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build actuator metrics security filter chain", e);
        }
    }

    @Bean
    @Order(3)
    public SecurityFilterChain gatewaySecurityFilterChain(
            HttpSecurity http, GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter) {
        try {
            http.csrf(csrf -> csrf.ignoringRequestMatchers(
                            "/v1/**",
                            "/actuator/**",
                            "/v3/api-docs",
                            "/v3/api-docs/**",
                            "/v3/api-docs.yaml",
                            "/swagger-ui/**",
                            "/swagger-ui.html"))
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.requestMatchers(
                                    "/v3/api-docs",
                                    "/v3/api-docs/**",
                                    "/v3/api-docs.yaml",
                                    "/swagger-ui/**",
                                    "/swagger-ui.html")
                            .permitAll()
                            .requestMatchers("/actuator/health")
                            .permitAll()
                            .requestMatchers(HttpMethod.GET, "/v1/permissions/catalog-version")
                            .permitAll()
                            .anyRequest()
                            .authenticated())
                    .addFilterBefore(permissionRegistrationSecretFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(gatewayHeaderAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));

            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build gateway security filter chain", e);
        }
    }

    private AuthenticationProvider prometheusScrapeAuthenticationProvider(
            String metricsUsername, String metricsPassword) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                String username = authentication.getName();
                String password = authentication.getCredentials() == null
                        ? ""
                        : authentication.getCredentials().toString();
                if (!constantTimeEquals(username, metricsUsername) || !constantTimeEquals(password, metricsPassword)) {
                    throw new BadCredentialsException("Invalid Prometheus scrape credentials");
                }
                return UsernamePasswordAuthenticationToken.authenticated(
                        username, null, List.of(new SimpleGrantedAuthority(METRICS_ROLE)));
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

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        try {
            return configuration.getAuthenticationManager();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize authentication manager", e);
        }
    }
}
