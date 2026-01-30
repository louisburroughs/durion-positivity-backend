package com.positivity.catalog.internal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

@Slf4j
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorizationManager -> authorizationManager
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilter(new JwtTokenFilter());
        return http.build();
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    static class JwtTokenFilter extends OncePerRequestFilter {
        private final RestClient restClient = RestClient.create();

        public JwtTokenFilter() {
            // Default constructor
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                @NonNull FilterChain filterChain)
                throws IOException, ServletException {
            String header = request.getHeader("Authorization");

            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    // Validate JWT
                    String securityServiceUrl = "http://pos-security-service/api/auth/validate";
                    String validationResult = restClient.post()
                            .uri(securityServiceUrl)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .retrieve()
                            .body(String.class);

                    if (validationResult == null) {
                        log.warn("JWT validation failed");
                        SecurityContextHolder.clearContext();
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                        return;
                    }

                    // Get roles
                    String rolesUrl = "http://pos-security-service/api/jwt/roles?token=" + token;
                    List<String> roles = restClient.get()
                            .uri(rolesUrl)
                            .retrieve()
                            .body(new org.springframework.core.ParameterizedTypeReference<>() {
                            });
                    if (roles == null) {
                        roles = List.of();
                    }

                    // Get username
                    String subjectUrl = "http://pos-security-service/api/jwt/subject?token=" + token;
                    String username = restClient.get()
                            .uri(subjectUrl)
                            .retrieve()
                            .body(String.class);
                    if (username == null) {
                        username = "unknown";
                    }

                    List<SimpleGrantedAuthority> authorities = roles.stream()
                            .map(SimpleGrantedAuthority::new)
                            .toList();
                    Authentication auth = new UsernamePasswordAuthenticationToken(
                            new User(username, "", authorities), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);

                } catch (Exception e) {
                    logger.error("Error during JWT validation or processing", e);
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token processing error");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
