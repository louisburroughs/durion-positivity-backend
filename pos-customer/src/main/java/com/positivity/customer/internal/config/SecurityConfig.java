package com.positivity.customer.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

/**
 * CRM Service Security Configuration.
 *
 * <p>
 * Imports {@link GatewaySecurityConfig} which provides:
 * </p>
 * <ul>
 * <li>Gateway-based authentication via X-Authorities and X-User headers</li>
 * <li>Stateless session management (JWT-based)</li>
 * <li>Method-level security (@PreAuthorize support)</li>
 * <li>Public access to actuator, swagger endpoints</li>
 * </ul>
 *
 * <h2>⚠️ Security Assumption</h2>
 * <p>
 * This configuration trusts headers injected by pos-api-gateway.
 * It assumes services are NOT directly exposed to external networks.
 * Network isolation must be enforced at infrastructure level.
 * </p>
 *
 * @see GatewaySecurityConfig
 */
@Slf4j
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Password encoder for any user/password operations.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Permits the public inquiry form, and nothing else (Story #1154).
     *
     * <p>Registered only when {@code pos.customer.inquiry.public.enabled=true} — the same flag
     * that registers {@code PublicInquiryController}. With the flag unset, this chain does not
     * exist and the imported {@link GatewaySecurityConfig} chain applies its
     * {@code anyRequest().authenticated()} rule, so the default posture is deny.
     *
     * <p>{@code securityMatcher} scopes it to exactly one path. {@code @Order(0)} puts it ahead
     * of {@link GatewaySecurityConfig}'s chain, which is itself {@code @Order(1)} and matches
     * any request — Spring Security refuses to start unless the catch-all chain is published
     * last, so a matching order here would be an unpredictable tie and a startup failure.
     *
     * <p>CSRF is disabled because this is a stateless JSON endpoint with no cookie-backed
     * session for an attacker to ride; session creation is stateless for the same reason.
     */
    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "pos.customer.inquiry.public", name = "enabled", havingValue = "true")
    public SecurityFilterChain publicInquiryFilterChain(HttpSecurity http) throws Exception {
        log.warn("Public unauthenticated inquiry endpoint is ENABLED at POST /v1/crm/public/inquiries. "
                + "Confirm captcha, gateway routing, and edge rate limiting are in place.");
        return http.securityMatcher("/v1/crm/public/inquiries")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/v1/crm/public/inquiries")
                        .permitAll()
                        // Only POST is public. Anything else on this path is refused outright
                        // rather than falling through to another chain.
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
