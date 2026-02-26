package com.positivity.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;

@Configuration
public class SecurityGatewayConfig {
    private static final String TOKEN2 = "token";
    private static final String AUTHORIZATION = "Authorization";
    private static final Logger log = LoggerFactory.getLogger(SecurityGatewayConfig.class);

    /**
     * Load-balanced WebClient builder for service discovery.
     * Uses Eureka to resolve "lb://security-service" to actual host:port.
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public WebClient securityWebClient(WebClient.Builder loadBalancedWebClientBuilder) {
        // Use Eureka service name - port is dynamic, resolved via service discovery
        return loadBalancedWebClientBuilder
                .baseUrl("lb://security-service")
                .build();
    }

    /**
     * Gateway-level CORS configuration for all routes.
     * <p>
     * This centralizes CORS policy at the network boundary (gateway).
     * Services are shielded from CORS concerns and only accessible via the gateway.
     * </p>
     */
    @Bean
    public WebFluxConfigurer corsConfigurer() {
        return new WebFluxConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }

    /**
     * Lightweight authentication/authorization filter for the gateway.
     * - Validates JWT via Security Service
     * - Retrieves expanded authorities and injects as `X-Authorities` header
     * - Optionally injects subject as `X-User`
     * - Bypasses public endpoints (actuator, swagger, api-docs)
     */
    @Bean
    public GlobalFilter authFilter(WebClient securityWebClient) {
        return (exchange, chain) -> {
            URI uri = exchange.getRequest().getURI();
            String path = uri.getPath();

            // Public paths bypass
            if (path.startsWith("/actuator") ||
                    path.contains("/actuator") ||
                    path.startsWith("/swagger-ui") ||
                    path.startsWith("/v3/api-docs") ||
                    path.contains("/v3/api-docs") ||
                    path.startsWith("/swagger-resources") ||
                    path.startsWith("/eureka")) {
                return chain.filter(exchange);
            }

            String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION);
            if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String token = authHeader.substring(7);

            // Validate token
            return securityWebClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/auth/validate")
                            .queryParam(TOKEN2, token)
                            .build())
                    .header(AUTHORIZATION, authHeader)
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
                                        .queryParam(TOKEN2, token)
                                        .build())
                                .header(AUTHORIZATION, authHeader)
                                .retrieve()
                                .bodyToMono(new ParameterizedTypeReference<Set<String>>() {
                                });

                        // Fetch subject (optional)
                        Mono<String> subjectMono = securityWebClient.get()
                                .uri(uriBuilder -> uriBuilder.path("/v1/auth/subject")
                                        .queryParam(TOKEN2, token)
                                        .build())
                                .header(AUTHORIZATION, authHeader)
                                .retrieve()
                                .bodyToMono(String.class)
                                .onErrorReturn("unknown");

                        Mono<String> personIdMono = securityWebClient.get()
                                .uri(uriBuilder -> uriBuilder.path("/v1/auth/person-id")
                                        .queryParam(TOKEN2, token)
                                        .build())
                                .header(AUTHORIZATION, authHeader)
                                .retrieve()
                                .bodyToMono(String.class)
                                .onErrorReturn("unknown");

                        return Mono.zip(authoritiesMono, subjectMono, personIdMono)
                                .flatMap(tuple -> {
                                    Set<String> authorities = tuple.getT1();
                                    String subject = tuple.getT2();
                                    String personId = tuple.getT3();
                                    String authHeaderValue = String.join(",", authorities);
                                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                                            .header("X-Authorities", authHeaderValue)
                                            .header("X-User", subject)
                                            .header("X-User-Id", personId)
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
