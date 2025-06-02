package com.positivity.catalog.config;

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
import org.springframework.web.client.RestTemplate;
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
                        .anyRequest().authenticated()
                )
                .addFilter(new JwtTokenFilter());
        return http.build();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    static class JwtTokenFilter extends OncePerRequestFilter {
        private final RestTemplate restTemplate = new RestTemplate();

        public JwtTokenFilter() {
            // Default constructor
        }
        @Override
        protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
                throws IOException, ServletException {
            String header = request.getHeader("Authorization");

            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    // Validate JWT
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set("Authorization", "Bearer " + token);
                    HttpEntity<String> entity = new HttpEntity<>(null, headers);
                    String securityServiceUrl = "http://pos-security-service/api/auth/validate";
                    ResponseEntity<String> validationResponse = restTemplate.exchange(
                            securityServiceUrl, HttpMethod.POST, entity, String.class);

                    if (!validationResponse.getStatusCode().is2xxSuccessful()) {
                        log.warn("JWT validation failed with status: {}", validationResponse.getStatusCode());
                        SecurityContextHolder.clearContext();
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                        return;
                    }

                    // Get roles
                    String rolesUrl = "http://pos-security-service/api/jwt/roles?token=" + token;
                    ResponseEntity<List<String>> rolesResponse = restTemplate.exchange(rolesUrl,
                            HttpMethod.GET,
                            null,
                            new org.springframework.core.ParameterizedTypeReference<>() {
                            });
                    List<?> body = rolesResponse.getBody();
                    List<String> roles = (rolesResponse.getStatusCode().is2xxSuccessful() && body != null)
                            ? body.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                            : List.of();

                    // Get username
                    String subjectUrl = "http://pos-security-service/api/jwt/subject?token=" + token;
                    ResponseEntity<String> subjectResponse = restTemplate.getForEntity(subjectUrl, String.class);
                    String username = (subjectResponse.getStatusCode().is2xxSuccessful() && subjectResponse.getBody() != null)
                            ? subjectResponse.getBody() : "unknown";

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

