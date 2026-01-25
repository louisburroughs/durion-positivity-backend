package com.positivity.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;

@Configuration
public class SecurityGatewayConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityGatewayConfig.class);

    @Value("${security.service.url:http://pos-security-service:8086}")
    private String securityServiceUrl;

    @Bean
    public WebClient securityWebClient() {
        return WebClient.builder()
                .baseUrl(securityServiceUrl)
                .build();
    }

    /**
     * Lightweight authentication/authorization filter for the gateway.
     * - Validates JWT via Security Service
     * - Retrieves expanded authorities and injects as `X-Authorities` header
     * - Optionally injects subject as `X-User`
     * - Bypasses public endpoints (actuator, swagger)
     */
    @Bean
    public GlobalFilter authFilter(WebClient securityWebClient) {
        return (exchange, chain) -> {
            URI uri = exchange.getRequest().getURI();
            String path = uri.getPath();

            // Public paths bypass
            if (path.startsWith("/actuator") ||
                    path.startsWith("/swagger-ui") ||
                    path.startsWith("/v3/api-docs") ||
                    path.startsWith("/swagger-resources") ||
                    path.startsWith("/eureka")) {
                return chain.filter(exchange);
            }

            String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String token = authHeader.substring(7);

            // Validate token
            return securityWebClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/auth/validate")
                            .queryParam("token", token)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<java.util.Map<String, Boolean>>() {
                    })
                    .flatMap(validMap -> {
                        boolean valid = validMap.getOrDefault("valid", false);
                        if (!valid) {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }
                        // Fetch authorities
                        Mono<Set<String>> authoritiesMono = securityWebClient.get()
                                .uri(uriBuilder -> uriBuilder.path("/v1/auth/authorities")
                                        .queryParam("token", token)
                                        .build())
                                .retrieve()
                                .bodyToMono(new ParameterizedTypeReference<Set<String>>() {
                                });

                        // Fetch subject (optional)
                        Mono<String> subjectMono = securityWebClient.get()
                                .uri(uriBuilder -> uriBuilder.path("/v1/auth/subject")
                                        .queryParam("token", token)
                                        .build())
                                .retrieve()
                                .bodyToMono(String.class)
                                .onErrorReturn("unknown");

                        return Mono.zip(authoritiesMono, subjectMono)
                                .flatMap(tuple -> {
                                    Set<String> authorities = tuple.getT1();
                                    String subject = tuple.getT2();
                                    String authHeaderValue = String.join(",", authorities);
                                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                                            .header("X-Authorities", authHeaderValue)
                                            .header("X-User", subject)
                                            .build();
                                    return chain.filter(exchange.mutate().request(mutated).build());
                                });
                    })
                    .onErrorResume(err -> {
                        log.error("Gateway auth error", err);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    });
        };
    }
}
