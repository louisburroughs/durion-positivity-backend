package com.positivity.invoice.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
        // Only the GET byte-download is exposed here; every other method on this path is
        // denied and (for legitimate mutating routes) handled by the authenticated
        // gateway chain. CSRF protection is intentionally left at its secure default:
        // it never challenges safe methods (GET/HEAD/OPTIONS), so the download needs no
        // token, and the chain is stateless (no session-borne ambient authority to forge).
        // Authorization is enforced inside the controller via the signed download token.
        http.securityMatcher(DOWNLOAD_PATTERN)
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, DOWNLOAD_PATTERN)
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
