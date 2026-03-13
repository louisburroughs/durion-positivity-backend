package com.positivity.gateway.config;

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
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class SecurityGatewayConfig {
    private static final String AUTHORIZATION = "Authorization";
    private static final String JWT_HEADER_ALG = "alg";
    private static final String UNSIGNED_ALG = "NONE";
    private static final String TEST_SIGNATURE_MARKER = "test-signature";
    private static final String HS256 = "HS256";
    private static final String HEADER_X_USER = "X-User";
    private static final String HEADER_X_USER_ID = "X-User-Id";
    private static final String HEADER_X_AUTHORITIES = "X-Authorities";
    private static final String HEADER_X_ROLES = "X-Roles";
    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_AUTHORITIES = "authorities";
    private static final String CLAIM_PERM_VER = "perm_ver";
    private static final String CLAIM_PERM_BITS = "perm_bits";
    private static final String TAG_REASON = "reason";
    private static final String LOG_JWT_AUTH_REJECTED = "JWT auth rejected path={} reason={} jti={}";
    private static final String UNKNOWN = "unknown";
    private static final String COUNTER_AUTH_TOKEN_VALIDATION_FAILURE = "auth.token.validation.failure";
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
            AuthRequestContext requestContext = createAuthRequestContext(exchange);
            if (isPublicPath(requestContext.path())) {
                return chain.filter(requestContext.strippedExchange());
            }

            Optional<String> token = extractBearerToken(requestContext.strippedRequest());
            if (token.isEmpty()) {
                return unauthorized(requestContext.strippedExchange());
            }

            return authenticateAndForward(requestContext, chain, token.get());
        };
    }

    private Mono<Void> authenticateAndForward(AuthRequestContext requestContext, GatewayFilterChain chain,
            String token) {
        Optional<String> preValidationReason = jwtPreValidationRejectionReason(token);
        if (preValidationReason.isPresent()) {
            return rejectTokenValidation(requestContext, preValidationReason.get(), UNKNOWN);
        }

        Optional<Claims> claims = parseClaims(token, requestContext);
        if (claims.isEmpty()) {
            return unauthorized(requestContext.strippedExchange());
        }

        return resolveIdentityAndForward(requestContext, chain, claims.get());
    }

    private Optional<Claims> parseClaims(String token, AuthRequestContext requestContext) {
        try {
            Jws<Claims> jwsClaims = Jwts.parser()
                    .verifyWith(secretKey)
                    // Enable issuer/audience requirements when JwtServiceImpl emits iss/aud claims.
                    // .requireIssuer("pos-security-service").requireAudience("api-gateway")
                    .build()
                    .parseSignedClaims(token);
            return Optional.of(jwsClaims.getPayload());
        } catch (JwtException ex) {
            recordTokenValidationFailure(requestContext, tokenValidationReason(ex), UNKNOWN);
            return Optional.empty();
        }
    }

    private Mono<Void> resolveIdentityAndForward(
            AuthRequestContext requestContext,
            GatewayFilterChain chain,
            Claims claims) {
        String jti = resolveJti(claims);
        String subject = claims.getSubject();
        if (!StringUtils.hasText(subject)) {
            incrementTaggedCounter("auth.user.identity.missing", "claim", "sub");
            return rejectAuth(requestContext, "missing_sub", jti);
        }

        Optional<String> resolvedAuthorities = resolveAuthoritiesHeader(claims, requestContext, jti);
        if (resolvedAuthorities.isEmpty()) {
            return unauthorized(requestContext.strippedExchange());
        }

        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                subject,
                claims.get(CLAIM_UID, String.class),
                resolvedAuthorities.get(),
                jti);

        return forwardAuthenticatedRequest(requestContext, chain, identity);
    }

    private Optional<String> resolveAuthoritiesHeader(Claims claims, AuthRequestContext requestContext, String jti) {
        Integer permVer = claims.get(CLAIM_PERM_VER, Integer.class);
        if (permVer == null) {
            Optional<List<String>> legacyAuthorities = extractLegacyAuthorities(claims);
            if (legacyAuthorities.isPresent()) {
                incrementCounter("auth.legacy.decode.count");
                LOG.warn("Legacy token (no perm_ver) - using authorities claim directly; path={} jti={}",
                        requestContext.path(), jti);
                return Optional.of(String.join(",", legacyAuthorities.get()));
            }
        }

        if (permVer == null || permVer != GatewayPermissionCatalog.CATALOG_VERSION) {
            String permVerValue = permVer == null ? "null" : String.valueOf(permVer);
            incrementTaggedCounter("auth.perm.catalog.version.unknown", CLAIM_PERM_VER, permVerValue);
            logAuthRejected(requestContext, "unknown_perm_ver:" + permVerValue, jti);
            return Optional.empty();
        }

        return decodeAuthoritiesFromPermBits(claims, requestContext, jti);
    }

    private Optional<String> decodeAuthoritiesFromPermBits(
            Claims claims,
            AuthRequestContext requestContext,
            String jti) {
        String permBits = claims.get(CLAIM_PERM_BITS, String.class);
        if (!StringUtils.hasText(permBits)) {
            if (authProperties.isTokenIdentityRequired()) {
                incrementTaggedCounter("auth.perm.decode.failure", TAG_REASON, "missing_claim");
                logAuthRejected(requestContext, "missing_perm_bits", jti);
                return Optional.empty();
            }
            permBits = "";
        }

        BitSet decodedPermissions = decodePermissionBits(permBits, requestContext, jti);
        if (decodedPermissions == null) {
            return Optional.empty();
        }

        List<String> authorities = decodedPermissions.stream()
                .mapToObj(GatewayPermissionCatalog::authorityForBit)
                .filter(Objects::nonNull)
                .toList();
        return Optional.of(String.join(",", authorities));
    }

    private BitSet decodePermissionBits(String permBits, AuthRequestContext requestContext, String jti) {
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(permBits.isEmpty() ? "" : permBits);
            return BitSet.valueOf(decodedBytes);
        } catch (Exception ex) {
            incrementTaggedCounter("auth.perm.decode.failure", TAG_REASON, "malformed_base64");
            logAuthRejected(requestContext, "malformed_perm_bits", jti);
            return null;
        }
    }

    private Mono<Void> forwardAuthenticatedRequest(
            AuthRequestContext requestContext,
            GatewayFilterChain chain,
            AuthenticatedIdentity identity) {
        if (authProperties.isRejectHeaderTokenMismatch()) {
            boolean mismatch = headerMismatch(requestContext.inboundIdentity().user(), identity.subject())
                    || headerMismatch(requestContext.inboundIdentity().userId(), identity.userId())
                    || headerMismatch(requestContext.inboundIdentity().authorities(), identity.authoritiesHeader());
            if (mismatch) {
                incrementTaggedCounter(COUNTER_AUTH_TOKEN_VALIDATION_FAILURE, TAG_REASON, "header_token_mismatch");
                return rejectAuth(requestContext, "header_token_mismatch", identity.jti());
            }
        }

        ServerHttpRequest authenticatedRequest = requestContext.strippedRequest().mutate().headers(headers -> {
            headers.set(HEADER_X_USER, identity.subject());
            if (StringUtils.hasText(identity.userId())) {
                headers.set(HEADER_X_USER_ID, identity.userId());
            }
            headers.set(HEADER_X_AUTHORITIES, identity.authoritiesHeader());
        }).build();

        return chain.filter(requestContext.strippedExchange().mutate().request(authenticatedRequest).build());
    }

    private Mono<Void> rejectTokenValidation(AuthRequestContext requestContext, String reason, String jti) {
        recordTokenValidationFailure(requestContext, reason, jti);
        return unauthorized(requestContext.strippedExchange());
    }

    private void recordTokenValidationFailure(AuthRequestContext requestContext, String reason, String jti) {
        incrementTaggedCounter(COUNTER_AUTH_TOKEN_VALIDATION_FAILURE, TAG_REASON, reason);
        logAuthRejected(requestContext, reason, jti);
    }

    private Mono<Void> rejectAuth(AuthRequestContext requestContext, String reason, String jti) {
        logAuthRejected(requestContext, reason, jti);
        return unauthorized(requestContext.strippedExchange());
    }

    private void logAuthRejected(AuthRequestContext requestContext, String reason, String jti) {
        LOG.warn(LOG_JWT_AUTH_REJECTED, requestContext.path(), reason, jti);
    }

    private String resolveJti(Claims claims) {
        return StringUtils.hasText(claims.getId()) ? claims.getId() : UNKNOWN;
    }

    private Optional<String> extractBearerToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return Optional.of(authHeader.substring(7));
    }

    private AuthRequestContext createAuthRequestContext(ServerWebExchange exchange) {
        ServerHttpRequest incomingRequest = exchange.getRequest();
        String path = incomingRequest.getURI().getPath();
        InboundIdentity inboundIdentity = new InboundIdentity(
                incomingRequest.getHeaders().getFirst(HEADER_X_USER),
                incomingRequest.getHeaders().getFirst(HEADER_X_USER_ID),
                incomingRequest.getHeaders().getFirst(HEADER_X_AUTHORITIES));

        ServerHttpRequest strippedRequest = incomingRequest.mutate().headers(headers -> {
            if (authProperties.isStripInboundIdentityHeaders()) {
                headers.remove(HEADER_X_USER);
                headers.remove(HEADER_X_USER_ID);
                headers.remove(HEADER_X_AUTHORITIES);
                headers.remove(HEADER_X_ROLES);
                incrementCounter("auth.header.strip.count");
            }
        }).build();

        ServerWebExchange strippedExchange = exchange.mutate().request(strippedRequest).build();
        return new AuthRequestContext(path, strippedRequest, strippedExchange, inboundIdentity);
    }

    private static Optional<List<String>> extractLegacyAuthorities(Claims claims) {
        Object legacyAuthorities = claims.get(CLAIM_AUTHORITIES);
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

    private record InboundIdentity(String user, String userId, String authorities) {
    }

    private record AuthRequestContext(
            String path,
            ServerHttpRequest strippedRequest,
            ServerWebExchange strippedExchange,
            InboundIdentity inboundIdentity) {
    }

    private record AuthenticatedIdentity(String subject, String userId, String authoritiesHeader, String jti) {
    }
}
