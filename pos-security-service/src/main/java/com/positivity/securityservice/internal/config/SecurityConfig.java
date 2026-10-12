package com.positivity.securityservice.internal.config;

import com.positivity.security.common.SecurityApiConstants;
import com.positivity.securityservice.internal.controller.StepUpController;
import com.positivity.securityservice.internal.security.GatewayHeaderAuthenticationFilter;
import com.positivity.securityservice.internal.security.InternalServiceSecretFilter;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.security.PermissionRegistrationSecretFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({LockoutPolicy.class, TenantSearchProperties.class, JwtLifetimeProperties.class})
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {
    private static final String V1_AUTH_LOGIN = "/v1/auth/login";
    private static final String V1_AUTH_SELF_REGISTER = "/v1/auth/self-register";
    /** Unauthenticated by design: the one-time activation token is the credential (ADR-0062 §7, WS2b-3). */
    private static final String V1_AUTH_ACTIVATE = "/v1/auth/activate";
    /** Unauthenticated by design: the login form searches it before any tenant is bound (ADR-0062 §3). */
    private static final String V1_AUTH_TENANTS = "/v1/auth/tenants";

    /**
     * Unauthenticated by design: the shared starter password is the credential, and it buys nothing
     * but a password of the account's own. Listed here as well as carrying {@code permitAll()} on the
     * method, because this chain ends in {@code anyRequest().authenticated()} — a path absent from
     * this list is refused here, before the controller's annotation is ever consulted.
     */
    private static final String V1_AUTH_ACTIVATE_STARTER = "/v1/auth/activate-starter";

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

    /**
     * The step-up credential check, {@value StepUpController#PATH} (CAP:550 S16, #2512), which pos-order
     * calls for a manager's cash-movement approval. It is a password check, so it is not reachable with
     * the mesh service credential every registering service holds: it takes a secret of its own (#2584),
     * {@value SecurityApiConstants#STEP_UP_SECRET_PROPERTY} ({@value
     * SecurityApiConstants#STEP_UP_SECRET_ENV_VAR}), held only by pos-order and presented on {@value
     * SecurityApiConstants#STEP_UP_SECRET_HEADER}, compared in constant time. Fail closed: unset, or set
     * to the mesh credential (which would undo the separation), every call answers 401. Otherwise as the
     * {@code /internal/**} chain below.
     */
    @Bean
    @Order(-1)
    public SecurityFilterChain stepUpFilterChain(
            HttpSecurity http,
            @Value("${" + SecurityApiConstants.STEP_UP_SECRET_PROPERTY + ":}") String stepUpSecret,
            @Value("${" + SecurityApiConstants.PERMISSION_SECRET_PROPERTY + ":}") String meshSecret,
            Clock clock,
            ObjectMapper objectMapper) {
        try {
            // CSRF safe to disable: as the /internal/** chain below. (Sonar S4502)
            http.securityMatcher(StepUpController.PATH)
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(
                            auth -> auth.anyRequest().hasAuthority(InternalServiceSecretFilter.INTERNAL_SERVICE_ROLE))
                    .addFilterBefore(
                            new InternalServiceSecretFilter(
                                    SecurityApiConstants.STEP_UP_SECRET_HEADER,
                                    effectiveStepUpSecret(stepUpSecret, meshSecret),
                                    clock,
                                    objectMapper),
                            UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));
            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build step-up filter chain", e);
        }
    }

    /**
     * The step-up secret the chain checks: the configured one, or none (every call refused) when it is
     * the mesh credential, since every registering service holds that one (#2584).
     */
    static String effectiveStepUpSecret(String stepUpSecret, String meshSecret) {
        if (SecurityApiConstants.hasSecret(stepUpSecret)
                && MessageDigest.isEqual(
                        stepUpSecret.getBytes(StandardCharsets.UTF_8), meshSecret.getBytes(StandardCharsets.UTF_8))) {
            log.error(
                    "{} equals {}: the step-up refuses every call until it has a secret of its own",
                    SecurityApiConstants.STEP_UP_SECRET_PROPERTY,
                    SecurityApiConstants.PERMISSION_SECRET_PROPERTY);
            return "";
        }
        if (!SecurityApiConstants.hasSecret(stepUpSecret)) {
            log.warn("{} is not set: the step-up refuses every call", SecurityApiConstants.STEP_UP_SECRET_PROPERTY);
        }
        return stepUpSecret;
    }

    /**
     * The rest of the service-to-service surface, {@code /internal/**} (CAP:550 S16, #2512). Stateless
     * and without CSRF — a mesh call carries no cookie and no CSRF token — and authenticated only by the
     * mesh service credential ({@link InternalServiceSecretFilter}); gateway identity headers and bearer
     * tokens are not accepted here. The gateway also refuses every {@code /internal} path at the edge.
     * The step-up check is matched first by {@link #stepUpFilterChain}, so the mesh credential never
     * reaches it.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain internalServiceFilterChain(
            HttpSecurity http,
            @Value("${pos.security.api-secret:}") String internalSecret,
            Clock clock,
            ObjectMapper objectMapper) {
        try {
            // CSRF safe to disable: STATELESS (below), no cookie is ever issued on this path, and
            // the only credential is a header a cross-site request cannot set. (Sonar S4502)
            http.securityMatcher("/internal/**")
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(
                            auth -> auth.anyRequest().hasAuthority(InternalServiceSecretFilter.INTERNAL_SERVICE_ROLE))
                    .addFilterBefore(
                            new InternalServiceSecretFilter(internalSecret, clock, objectMapper),
                            UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint)
                            .accessDeniedHandler(jsonAccessDeniedHandler));
            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build internal service filter chain", e);
        }
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http, GatewayHeaderAuthenticationFilter gatewayHeaderAuthenticationFilter) {
        try {
            // CSRF safe to skip: this chain is STATELESS (below) and authenticates via
            // bearer JWT / gateway headers, never a session cookie. With no cookie-borne
            // ambient authority, there is nothing for a cross-site request to forge; the
            // login/refresh endpoints are the credential exchange itself. (Sonar S4502)
            http.securityMatcher("/v1/auth/**")
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/v1/auth/**"))
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.requestMatchers(
                                    "/v1/auth/refresh",
                                    "/v1/auth/validate",
                                    V1_AUTH_LOGIN,
                                    V1_AUTH_SELF_REGISTER,
                                    V1_AUTH_ACTIVATE,
                                    V1_AUTH_ACTIVATE_STARTER,
                                    V1_AUTH_TENANTS)
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
            // CSRF safe to disable: single Prometheus scrape endpoint, STATELESS (below)
            // and guarded by HTTP Basic. No session cookie is issued, so there is no
            // ambient authority a cross-site request could exploit. (Sonar S4502)
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
            // CSRF safe to skip: this chain is STATELESS (below) and authenticates via
            // bearer JWT / validated gateway headers, never a session cookie. Docs and
            // actuator/health are public reads. With no cookie-borne ambient authority
            // there is nothing for a cross-site request to forge. (Sonar S4502)
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
