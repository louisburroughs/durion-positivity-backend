package com.positivity.extractionworker.internal.config;

import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
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
 * The worker API is not the gateway's: pos-accounting calls it directly with a shared secret, and the
 * gateway has no route to this service. {@code /extraction-worker/v1/**} gets its own chain, ordered ahead
 * of the imported gateway one, in which {@link WorkerSecretFilter} is the only authentication and
 * {@link TenantLabelFilter} runs right after it. Both filters are built here rather than as beans so Boot
 * does not also register them as plain servlet filters outside the chain. Everything else (actuator,
 * OpenAPI docs) keeps the gateway chain's rules, which is what guards the Prometheus scrape.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    @Bean
    @Order(0)
    @SuppressWarnings("java:S4502") // CSRF not needed: stateless service-to-service call, secret in a header
    public SecurityFilterChain workerApiFilterChain(
            HttpSecurity http, ExtractionWorkerProperties properties, Clock clock, ObjectMapper objectMapper) {
        WorkerSecretFilter secretFilter = new WorkerSecretFilter(properties.apiSecret(), clock, objectMapper);
        http.securityMatcher(WorkerSecretFilter.PATH_PREFIX + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(
                        handler -> handler.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(secretFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantLabelFilter(clock, objectMapper), WorkerSecretFilter.class);
        return http.build();
    }
}
