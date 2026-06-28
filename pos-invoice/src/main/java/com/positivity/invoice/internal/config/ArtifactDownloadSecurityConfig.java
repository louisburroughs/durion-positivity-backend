package com.positivity.invoice.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits the public, token-authorized artifact download endpoint.
 *
 * <p>The byte download cannot carry a gateway JWT (browsers can't add an Authorization header to a
 * direct download link), so this higher-precedence filter chain matches only that path and permits
 * it. Authorization is enforced inside the controller by the signed download token. All other
 * requests fall through to the shared {@code GatewaySecurityConfig} chain (which requires auth).
 */
@Configuration
public class ArtifactDownloadSecurityConfig {

    static final String DOWNLOAD_PATTERN = "/v1/invoices/*/artifacts/*/download";

    @Bean
    @Order(0)
    public SecurityFilterChain artifactDownloadFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(DOWNLOAD_PATTERN)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
