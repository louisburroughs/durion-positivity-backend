package com.positivity.platformsender.internal.config;

import com.positivity.platformsender.internal.security.SenderSecretFilter;
import com.positivity.security.common.GatewaySecurityConfig;
import java.time.Clock;
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
 * The send API is not the gateway's: pos-marketing calls it directly with a shared secret (FI-2 §1),
 * and the gateway has no route to this service. {@code /platform-sender/v1/**} gets its own chain,
 * ordered ahead of the imported gateway one, in which {@link SenderSecretFilter} is the only
 * authentication; gateway {@code X-Authorities} headers carry no weight on it. The filter is built
 * here rather than as a bean so Boot does not also register it as a plain servlet filter outside
 * the chain. Everything else (actuator, OpenAPI docs) keeps the gateway chain's rules.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    @Bean
    @Order(0)
    @SuppressWarnings("java:S4502") // CSRF not needed: stateless service-to-service call, secret in a header
    public SecurityFilterChain senderApiFilterChain(
            HttpSecurity http, SenderProperties properties, Clock clock, ObjectMapper objectMapper) {
        http.securityMatcher(SenderSecretFilter.PATH_PREFIX + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(
                        handler -> handler.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new SenderSecretFilter(properties.apiSecret(), clock, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
