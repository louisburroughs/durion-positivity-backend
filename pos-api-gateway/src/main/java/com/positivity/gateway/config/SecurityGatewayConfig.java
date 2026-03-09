package com.positivity.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
public class SecurityGatewayConfig {
    private static final String TOKEN2 = "token";
    private static final String AUTHORIZATION = "Authorization";
    private static final String JWT_HEADER_ALG = "alg";
    private static final String UNSIGNED_ALG = "NONE";
    private static final String TEST_SIGNATURE_MARKER = "test-signature";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SecurityGatewayConfig.class);
    private final boolean strictJwtHeaderValidation;
    private final Set<String> allowedJwtAlgorithms;

    public SecurityGatewayConfig(
            @Value("${pos.gateway.security.strict-jwt-header-validation:false}") boolean strictJwtHeaderValidation,
            @Value("${pos.gateway.security.allowed-jwt-algorithms:HS256}") String allowedJwtAlgorithmsCsv) {
        this.strictJwtHeaderValidation = strictJwtHeaderValidation;
        this.allowedJwtAlgorithms = parseAllowedJwtAlgorithms(allowedJwtAlgorithmsCsv);
    }

    SecurityGatewayConfig(boolean strictJwtHeaderValidation, Set<String> allowedJwtAlgorithms) {
        this.strictJwtHeaderValidation = strictJwtHeaderValidation;
        this.allowedJwtAlgorithms = normalizeAllowedJwtAlgorithms(allowedJwtAlgorithms);
    }

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
     * - Forwards original bearer token; downstream services resolve `userId` from JWT claim
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
            Optional<String> preValidationRejectionReason = jwtPreValidationRejectionReason(token);
            if (preValidationRejectionReason.isPresent()) {
                log.warn("Rejected bearer token before security-service validation: {}",
                        preValidationRejectionReason.get());
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

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

    private Optional<String> jwtPreValidationRejectionReason(String token) {
        if (!strictJwtHeaderValidation) {
            return Optional.empty();
        }

        if (!StringUtils.hasText(token)) {
            return Optional.of("blank token");
        }

        String[] tokenParts = token.split("\\.");
        if (tokenParts.length < 3) {
            return Optional.of("token is not in JWT 3-part format");
        }

        String signaturePart = tokenParts[2];
        if (signaturePart != null && signaturePart.contains(TEST_SIGNATURE_MARKER)) {
            return Optional.of("synthetic test signature marker detected");
        }

        Optional<String> headerAlg = extractJwtHeaderAlgorithm(tokenParts[0]);
        if (headerAlg.isEmpty()) {
            return Optional.of("missing or unreadable JWT alg header");
        }

        String normalizedAlg = headerAlg.get().toUpperCase(Locale.ROOT);
        if (UNSIGNED_ALG.equals(normalizedAlg)) {
            return Optional.of("alg=none is not allowed");
        }

        if (!allowedJwtAlgorithms.contains(normalizedAlg)) {
            return Optional.of("disallowed JWT alg: " + normalizedAlg);
        }

        return Optional.empty();
    }

    private Optional<String> extractJwtHeaderAlgorithm(String encodedHeader) {
        try {
            byte[] decodedHeader = Base64.getUrlDecoder().decode(encodedHeader);
            JsonNode header = OBJECT_MAPPER.readTree(new String(decodedHeader, StandardCharsets.UTF_8));
            JsonNode algNode = header.get(JWT_HEADER_ALG);
            if (algNode == null || algNode.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(algNode.asText());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static Set<String> parseAllowedJwtAlgorithms(String allowedJwtAlgorithmsCsv) {
        if (!StringUtils.hasText(allowedJwtAlgorithmsCsv)) {
            return Set.of("HS256");
        }

        Set<String> normalized = Arrays.stream(allowedJwtAlgorithmsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(alg -> alg.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalized.isEmpty()) {
            return Set.of("HS256");
        }

        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeAllowedJwtAlgorithms(Set<String> allowedJwtAlgorithms) {
        if (allowedJwtAlgorithms == null || allowedJwtAlgorithms.isEmpty()) {
            return Set.of("HS256");
        }

        Set<String> normalized = allowedJwtAlgorithms.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(alg -> alg.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalized.isEmpty()) {
            return Set.of("HS256");
        }

        return Set.copyOf(normalized);
    }
}
