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
        // CSRF is left at its secure default rather than disabled: it never challenges
        // safe methods (GET/HEAD/OPTIONS), so the byte-download — and clients that probe
        // it with HEAD or a CORS OPTIONS preflight — work without a token, while an unsafe
        // method would be rejected (this path has only the GET download handler, no
        // mutating routes). The chain is stateless, so there is no session-borne ambient
        // authority for a cross-site request to forge; authorization is enforced inside
        // the controller via the signed download token.
        http.securityMatcher(DOWNLOAD_PATTERN)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
