package com.positivity.accounting.internal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableMethodSecurity
public class AccountingSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AccountingSecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RestClient restClient) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtTokenFilter(restClient), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    /**
     * JWT filter that delegates token validation and authority expansion to
     * pos-security-service.
     */
    static class JwtTokenFilter extends OncePerRequestFilter {
        private final RestClient restClient;

        JwtTokenFilter(RestClient restClient) {
            this.restClient = restClient;
        }

        @Override
        protected void doFilterInternal(@NonNull HttpServletRequest request,
                @NonNull HttpServletResponse response,
                @NonNull FilterChain filterChain) throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = header.substring(7);
            try {
                if (!isTokenValid(token)) {
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                    return;
                }

                Set<SimpleGrantedAuthority> authorities = fetchAuthorities(token);
                String username = fetchSubject(token);

                Authentication auth = new UsernamePasswordAuthenticationToken(
                        new User(username, "", authorities),
                        null,
                        authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (RestClientException e) {
                log.error("JWT validation error", e);
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token validation error");
                return;
            }

            filterChain.doFilter(request, response);
        }

        private boolean isTokenValid(String token) {
            try {
                Map response = restClient.get()
                        .uri("http://pos-security-service/v1/auth/validate?token={token}", token)
                        .retrieve()
                        .body(Map.class);
                if (response == null) {
                    return false;
                }
                Object valid = response.get("valid");
                return Boolean.TRUE.equals(valid);
            } catch (RestClientException e) {
                log.warn("Token validation failed", e);
                return false;
            }
        }

        private String fetchSubject(String token) {
            try {
                String subject = restClient.get()
                        .uri("http://pos-security-service/v1/auth/subject?token={token}", token)
                        .retrieve()
                        .body(String.class);
                if (subject != null && !subject.isBlank()) {
                    return subject;
                }
            } catch (RestClientException e) {
                log.warn("Failed to fetch subject from security-service", e);
            }
            return "unknown";
        }

        private Set<SimpleGrantedAuthority> fetchAuthorities(String token) {
            Set<String> authorityStrings = new HashSet<>();
            try {
                List authList = restClient.get()
                        .uri("http://pos-security-service/v1/auth/authorities?token={token}", token)
                        .retrieve()
                        .body(List.class);
                if (authList != null) {
                    authorityStrings.addAll((List<String>) authList);
                }
            } catch (RestClientException e) {
                log.warn("Failed to fetch authorities; falling back to roles", e);
            }

            if (authorityStrings.isEmpty()) {
                try {
                    List rolesList = restClient.get()
                            .uri("http://pos-security-service/v1/auth/roles?token={token}", token)
                            .retrieve()
                            .body(List.class);
                    if (rolesList != null) {
                        authorityStrings.addAll((List<String>) rolesList);
                    }
                } catch (RestClientException e) {
                    log.warn("Failed to fetch roles from security-service", e);
                }
            }

            if (authorityStrings.isEmpty()) {
                return Collections.emptySet();
            }

            return authorityStrings.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(SimpleGrantedAuthority::new)
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
