package com.positivity.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class SecurityGatewayConfig {
    private static final String AUTHORIZATION = "Authorization";
    private static final String JWT_HEADER_ALG = "alg";
    private static final String UNSIGNED_ALG = "NONE";
    private static final String TEST_SIGNATURE_MARKER = "test-signature";
    private static final String HS256 = "HS256";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(SecurityGatewayConfig.class);

    private final SecretKey secretKey;
    private final boolean strictJwtHeaderValidation;
    private final Set<String> allowedJwtAlgorithms;
    private final GatewayAuthProperties authProperties;
    private final MeterRegistry meterRegistry;

    @Autowired
    public SecurityGatewayConfig(
            @Value("${security.jwt.secret}") @NonNull String jwtSecret,
            @Value("${pos.gateway.security.strict-jwt-header-validation:false}") boolean strictJwtHeaderValidation,
            @Value("${pos.gateway.security.allowed-jwt-algorithms:HS256}") String allowedJwtAlgorithmsCsv,
            @NonNull GatewayAuthProperties authProperties,
            @NonNull MeterRegistry meterRegistry) {
        this(jwtSecret,
                strictJwtHeaderValidation,
                parseAllowedJwtAlgorithms(allowedJwtAlgorithmsCsv),
                authProperties,
                meterRegistry);
    }

        SecurityGatewayConfig(@NonNull String jwtSecret, boolean strictJwtHeaderValidation,
            Set<String> allowedJwtAlgorithms,
            @NonNull GatewayAuthProperties authProperties,
            @NonNull MeterRegistry meterRegistry) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.strictJwtHeaderValidation = strictJwtHeaderValidation;
        this.allowedJwtAlgorithms = normalizeAllowedJwtAlgorithms(allowedJwtAlgorithms);
        this.authProperties = authProperties;
        this.meterRegistry = meterRegistry;
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

    @Bean
    public GlobalFilter authFilter() {
        return (exchange, chain) -> {
            ServerHttpRequest incomingRequest = exchange.getRequest();
            URI uri = incomingRequest.getURI();
            String path = uri.getPath();
            String inboundUser = incomingRequest.getHeaders().getFirst("X-User");
            String inboundUserId = incomingRequest.getHeaders().getFirst("X-User-Id");
            String inboundAuthorities = incomingRequest.getHeaders().getFirst("X-Authorities");

            ServerHttpRequest strippedRequest = incomingRequest.mutate().headers(headers -> {
                if (authProperties.isStripInboundIdentityHeaders()) {
                    headers.remove("X-User");
                    headers.remove("X-User-Id");
                    headers.remove("X-Authorities");
                    headers.remove("X-Roles");
                    incrementCounter("auth.header.strip.count");
                }
            }).build();
            var strippedExchange = exchange.mutate().request(strippedRequest).build();

            if (isPublicPath(path)) {
                return chain.filter(strippedExchange);
            }

            String authHeader = strippedRequest.getHeaders().getFirst(AUTHORIZATION);
            if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
                return unauthorized(strippedExchange);
            }

            String token = authHeader.substring(7);
            Optional<String> preValidationReason = jwtPreValidationRejectionReason(token);
            if (preValidationReason.isPresent()) {
                String reason = preValidationReason.get();
                incrementTaggedCounter("auth.token.validation.failure", "reason", reason);
                LOG.warn("JWT auth rejected path={} reason={} jti={}", path, reason, "unknown");
                return unauthorized(strippedExchange);
            }

            Claims claims;
            try {
                Jws<Claims> jwsClaims = Jwts.parser()
                        .verifyWith(secretKey)
                        // TODO: add .requireIssuer("pos-security-service").requireAudience("api-gateway")
                        // after JwtServiceImpl emits iss/aud claims.
                        .build()
                        .parseSignedClaims(token);
                claims = jwsClaims.getPayload();
            } catch (JwtException ex) {
                String reason = tokenValidationReason(ex);
                incrementTaggedCounter("auth.token.validation.failure", "reason", reason);
                LOG.warn("JWT auth rejected path={} reason={} jti={}", path, reason, "unknown");
                return unauthorized(strippedExchange);
            }

            String jti = StringUtils.hasText(claims.getId()) ? claims.getId() : "unknown";
            String subject = claims.getSubject();
            if (!StringUtils.hasText(subject)) {
                incrementTaggedCounter("auth.user.identity.missing", "claim", "sub");
                LOG.warn("JWT auth rejected path={} reason={} jti={}", path, "missing_sub", jti);
                return unauthorized(strippedExchange);
            }

            Integer permVer = claims.get("perm_ver", Integer.class);
            if (permVer == null) {
                // TODO: remove after perm_bits rollout complete
                Optional<List<String>> legacyAuthorities = extractLegacyAuthorities(claims);
                if (legacyAuthorities.isPresent()) {
                    incrementCounter("auth.legacy.decode.count");
                    LOG.warn("Legacy token (no perm_ver) - using authorities claim directly; path={} jti={}", path, jti);
                    String legacyAuthoritiesHeader = String.join(",", legacyAuthorities.get());
                    return forwardAuthenticatedRequest(
                            strippedExchange,
                            chain,
                            strippedRequest,
                            subject,
                            claims.get("uid", String.class),
                            inboundUser,
                            inboundUserId,
                            inboundAuthorities,
                            legacyAuthoritiesHeader,
                            jti,
                            path);
                }
            }
            if (permVer == null || permVer != GatewayPermissionCatalog.CATALOG_VERSION) {
                String permVerValue = permVer == null ? "null" : String.valueOf(permVer);
                incrementTaggedCounter("auth.perm.catalog.version.unknown", "perm_ver", permVerValue);
                LOG.warn("JWT auth rejected path={} reason={} jti={}", path,
                        "unknown_perm_ver:" + permVerValue, jti);
                return unauthorized(strippedExchange);
            }

            String permBits = claims.get("perm_bits", String.class);
            if (!StringUtils.hasText(permBits)) {
                if (authProperties.isTokenIdentityRequired()) {
                    incrementTaggedCounter("auth.perm.decode.failure", "reason", "missing_claim");
                    LOG.warn("JWT auth rejected path={} reason={} jti={}", path, "missing_perm_bits", jti);
                    return unauthorized(strippedExchange);
                }
                permBits = "";
            }

            BitSet decodedPermissions;
            try {
                byte[] decodedBytes = Base64.getUrlDecoder().decode(permBits.isEmpty() ? "" : permBits);
                decodedPermissions = BitSet.valueOf(decodedBytes);
            } catch (Exception ex) {
                incrementTaggedCounter("auth.perm.decode.failure", "reason", "malformed_base64");
                LOG.warn("JWT auth rejected path={} reason={} jti={}", path, "malformed_perm_bits", jti);
                return unauthorized(strippedExchange);
            }

            List<String> authorities = decodedPermissions.stream()
                    .mapToObj(GatewayPermissionCatalog::authorityForBit)
                    .filter(Objects::nonNull)
                    .toList();
            String authoritiesHeader = String.join(",", authorities);

            return forwardAuthenticatedRequest(
                    strippedExchange,
                    chain,
                    strippedRequest,
                    subject,
                    claims.get("uid", String.class),
                    inboundUser,
                    inboundUserId,
                    inboundAuthorities,
                    authoritiesHeader,
                    jti,
                    path);
        };
    }

    private Mono<Void> forwardAuthenticatedRequest(
            org.springframework.web.server.ServerWebExchange strippedExchange,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
            ServerHttpRequest strippedRequest,
            String subject,
            String userId,
            String inboundUser,
            String inboundUserId,
            String inboundAuthorities,
            String authoritiesHeader,
            String jti,
            String path) {
        if (authProperties.isRejectHeaderTokenMismatch()) {
            boolean mismatch = headerMismatch(inboundUser, subject)
                    || headerMismatch(inboundUserId, userId)
                    || headerMismatch(inboundAuthorities, authoritiesHeader);
            if (mismatch) {
                incrementTaggedCounter("auth.token.validation.failure", "reason", "header_token_mismatch");
                LOG.warn("JWT auth rejected path={} reason={} jti={}", path, "header_token_mismatch", jti);
                return unauthorized(strippedExchange);
            }
        }

        ServerHttpRequest authenticatedRequest = strippedRequest.mutate().headers(headers -> {
            headers.set("X-User", subject);
            if (StringUtils.hasText(userId)) {
                headers.set("X-User-Id", userId);
            }
            headers.set("X-Authorities", authoritiesHeader);
        }).build();

        return chain.filter(strippedExchange.mutate().request(authenticatedRequest).build());
    }

    private static Optional<List<String>> extractLegacyAuthorities(Claims claims) {
        Object legacyAuthorities = claims.get("authorities");
        if (!(legacyAuthorities instanceof List<?> authoritiesList)) {
            return Optional.empty();
        }

        List<String> normalizedAuthorities = authoritiesList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(StringUtils::hasText)
                .toList();

        if (normalizedAuthorities.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(normalizedAuthorities);
    }

    private static boolean isPublicPath(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-resources")
                || path.startsWith("/eureka");
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
            return Set.of(HS256);
        }

        Set<String> normalized = Arrays.stream(allowedJwtAlgorithmsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(alg -> alg.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalized.isEmpty()) {
            return Set.of(HS256);
        }

        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeAllowedJwtAlgorithms(Set<String> allowedJwtAlgorithms) {
        if (allowedJwtAlgorithms == null || allowedJwtAlgorithms.isEmpty()) {
            return Set.of(HS256);
        }

        Set<String> normalized = allowedJwtAlgorithms.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(alg -> alg.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalized.isEmpty()) {
            return Set.of(HS256);
        }

        return Set.copyOf(normalized);
    }

    private static Mono<Void> unauthorized(org.springframework.web.server.ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private String tokenValidationReason(JwtException ex) {
        if (ex instanceof ExpiredJwtException) {
            return "expired";
        }
        if (ex instanceof SignatureException) {
            return "invalid_signature";
        }
        if (ex instanceof MalformedJwtException) {
            return "malformed";
        }
        if (ex instanceof UnsupportedJwtException) {
            return "unsupported";
        }
        return "invalid_token";
    }

    private void incrementCounter(String counterName) {
        Counter.builder(counterName)
                .register(meterRegistry)
                .increment();
    }

    private void incrementTaggedCounter(String counterName, String tagKey, String tagValue) {
        Counter.builder(counterName)
                .tag(tagKey, tagValue)
                .register(meterRegistry)
                .increment();
    }

    private static boolean headerMismatch(String inboundValue, String expectedValue) {
        if (!StringUtils.hasText(inboundValue)) {
            return false;
        }
        if (!StringUtils.hasText(expectedValue)) {
            return true;
        }
        return !inboundValue.equals(expectedValue);
    }
}
