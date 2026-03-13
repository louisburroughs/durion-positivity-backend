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
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class SecurityGatewayConfig {
    private static final String AUTHORIZATION = "Authorization";
    private static final String HEADER_X_AUTHORITIES = "X-Authorities";
    private static final String HEADER_X_ROLES = "X-Roles";
    private static final String HEADER_X_USER = "X-User";
    private static final String HEADER_X_USER_ID = "X-User-Id";
    private static final String JWT_HEADER_ALG = "alg";
    private static final String CLAIM_PERMISSION_VERSION = "perm_ver";
    private static final String LOG_JWT_AUTH_REJECTED = "JWT auth rejected path={} reason={} jti={}";
    private static final String METRIC_AUTH_HEADER_STRIP_COUNT = "auth.header.strip.count";
    private static final String METRIC_AUTH_LEGACY_DECODE_COUNT = "auth.legacy.decode.count";
    private static final String METRIC_AUTH_PERMISSION_CATALOG_UNKNOWN = "auth.perm.catalog.version.unknown";
    private static final String METRIC_AUTH_PERMISSION_DECODE_FAILURE = "auth.perm.decode.failure";
    private static final String METRIC_AUTH_TOKEN_VALIDATION_FAILURE = "auth.token.validation.failure";
    private static final String METRIC_AUTH_USER_IDENTITY_MISSING = "auth.user.identity.missing";
    private static final String REJECTION_REASON_TAG = "reason";
    private static final String UNSIGNED_ALG = "NONE";
    private static final String TEST_SIGNATURE_MARKER = "test-signature";
    private static final String HS256 = "HS256";
    private static final String UNKNOWN_JTI = "unknown";
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
        return (exchange, chain) -> authenticateRequest(createAuthRequestContext(exchange), chain);
    }

    private Mono<Void> authenticateRequest(AuthRequestContext context, GatewayFilterChain chain) {
        if (isPublicPath(context.path())) {
            return chain.filter(context.exchange());
        }

        Optional<String> token = extractBearerToken(context.request());
        if (token.isEmpty()) {
            return unauthorized(context.exchange());
        }

        Optional<String> preValidationReason = jwtPreValidationRejectionReason(token.get());
        if (preValidationReason.isPresent()) {
            return rejectAuthentication(
                    context,
                    METRIC_AUTH_TOKEN_VALIDATION_FAILURE,
                    REJECTION_REASON_TAG,
                    preValidationReason.get(),
                    preValidationReason.get(),
                    UNKNOWN_JTI);
        }

        Optional<Claims> claims = parseVerifiedClaims(token.get(), context);
        if (claims.isEmpty()) {
            return unauthorized(context.exchange());
        }

        Optional<AuthenticatedIdentity> identity = resolveAuthenticatedIdentity(claims.get(), context);
        if (identity.isEmpty()) {
            return unauthorized(context.exchange());
        }

        return forwardAuthenticatedRequest(context, chain, identity.get());
    }

    private AuthRequestContext createAuthRequestContext(ServerWebExchange exchange) {
        ServerHttpRequest incomingRequest = exchange.getRequest();
        URI uri = incomingRequest.getURI();
        InboundIdentityHeaders inboundHeaders = new InboundIdentityHeaders(
                incomingRequest.getHeaders().getFirst(HEADER_X_USER),
                incomingRequest.getHeaders().getFirst(HEADER_X_USER_ID),
                incomingRequest.getHeaders().getFirst(HEADER_X_AUTHORITIES));

        ServerHttpRequest strippedRequest = incomingRequest.mutate().headers(headers -> {
            if (authProperties.isStripInboundIdentityHeaders()) {
                headers.remove(HEADER_X_USER);
                headers.remove(HEADER_X_USER_ID);
                headers.remove(HEADER_X_AUTHORITIES);
                headers.remove(HEADER_X_ROLES);
                incrementCounter(METRIC_AUTH_HEADER_STRIP_COUNT);
            }
        }).build();

        ServerWebExchange strippedExchange = exchange.mutate().request(strippedRequest).build();
        return new AuthRequestContext(strippedExchange, strippedRequest, uri.getPath(), inboundHeaders);
    }

    private Optional<String> extractBearerToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return Optional.of(authHeader.substring(7));
    }

    private Optional<Claims> parseVerifiedClaims(String token, AuthRequestContext context) {
        try {
            Jws<Claims> jwsClaims = Jwts.parser()
                    .verifyWith(secretKey)
                    // Issuer and audience validation will be enabled once emitted by the token service.
                    .build()
                    .parseSignedClaims(token);
            return Optional.of(jwsClaims.getPayload());
        } catch (JwtException ex) {
            String reason = tokenValidationReason(ex);
            rejectAuthentication(
                    context,
                    METRIC_AUTH_TOKEN_VALIDATION_FAILURE,
                    REJECTION_REASON_TAG,
                    reason,
                    reason,
                    UNKNOWN_JTI);
            return Optional.empty();
        }
    }

    private Optional<AuthenticatedIdentity> resolveAuthenticatedIdentity(Claims claims, AuthRequestContext context) {
        String jti = StringUtils.hasText(claims.getId()) ? claims.getId() : UNKNOWN_JTI;
        String subject = claims.getSubject();
        if (!StringUtils.hasText(subject)) {
            rejectAuthentication(context, METRIC_AUTH_USER_IDENTITY_MISSING, "claim", "sub", "missing_sub", jti);
            return Optional.empty();
        }

        Optional<String> legacyAuthoritiesHeader = resolveLegacyAuthoritiesHeader(claims, context, jti);
        if (legacyAuthoritiesHeader.isPresent()) {
            return Optional.of(new AuthenticatedIdentity(subject, claims.get("uid", String.class),
                    legacyAuthoritiesHeader.get(), jti));
        }

        Integer permVer = claims.get(CLAIM_PERMISSION_VERSION, Integer.class);
        if (permVer == null || permVer != GatewayPermissionCatalog.CATALOG_VERSION) {
            String permVerValue = permVer == null ? "null" : String.valueOf(permVer);
            rejectAuthentication(
                    context,
                    METRIC_AUTH_PERMISSION_CATALOG_UNKNOWN,
                    CLAIM_PERMISSION_VERSION,
                    permVerValue,
                    "unknown_perm_ver:" + permVerValue,
                    jti);
            return Optional.empty();
        }

        Optional<BitSet> decodedPermissions = decodePermissionBits(claims, context, jti);
        if (decodedPermissions.isEmpty()) {
            return Optional.empty();
        }

        String authoritiesHeader = buildAuthoritiesHeader(decodedPermissions.get());
        return Optional.of(new AuthenticatedIdentity(subject, claims.get("uid", String.class), authoritiesHeader, jti));
    }

    private Optional<String> resolveLegacyAuthoritiesHeader(Claims claims, AuthRequestContext context, String jti) {
        Integer permVer = claims.get(CLAIM_PERMISSION_VERSION, Integer.class);
        if (permVer != null) {
            return Optional.empty();
        }

        Optional<List<String>> legacyAuthorities = extractLegacyAuthorities(claims);
        if (legacyAuthorities.isEmpty()) {
            return Optional.empty();
        }

        incrementCounter(METRIC_AUTH_LEGACY_DECODE_COUNT);
        LOG.warn("Legacy token (no perm_ver) - using authorities claim directly; path={} jti={}", context.path(), jti);
        return Optional.of(String.join(",", legacyAuthorities.get()));
    }

    private Optional<BitSet> decodePermissionBits(Claims claims, AuthRequestContext context, String jti) {
        String permBits = claims.get("perm_bits", String.class);
        if (!StringUtils.hasText(permBits)) {
            if (authProperties.isTokenIdentityRequired()) {
                rejectAuthentication(
                        context,
                        METRIC_AUTH_PERMISSION_DECODE_FAILURE,
                        REJECTION_REASON_TAG,
                        "missing_claim",
                        "missing_perm_bits",
                        jti);
                return Optional.empty();
            }
            return Optional.of(BitSet.valueOf(Base64.getUrlDecoder().decode("")));
        }

        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(permBits);
            return Optional.of(BitSet.valueOf(decodedBytes));
        } catch (Exception ex) {
            rejectAuthentication(
                    context,
                    METRIC_AUTH_PERMISSION_DECODE_FAILURE,
                    REJECTION_REASON_TAG,
                    "malformed_base64",
                    "malformed_perm_bits",
                    jti);
            return Optional.empty();
        }
    }

    private String buildAuthoritiesHeader(BitSet decodedPermissions) {
        List<String> authorities = decodedPermissions.stream()
                .mapToObj(GatewayPermissionCatalog::authorityForBit)
                .filter(Objects::nonNull)
                .toList();
        return String.join(",", authorities);
    }

    /**
     * Compares the X-Authorities header value with the authorities derived from the token
     * in a way that is insensitive to ordering and extraneous whitespace.
     */
    private boolean authoritiesHeaderMismatch(String headerAuthorities, String tokenAuthorities) {
        if (!StringUtils.hasText(headerAuthorities)) {
            // Only enforce mismatch checks when the inbound spoofable header is actually present.
            return false;
        }
        if (!StringUtils.hasText(tokenAuthorities)) {
            return true;
        }

        Set<String> headerSet = Arrays.stream(headerAuthorities.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> tokenSet = Arrays.stream(tokenAuthorities.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return !headerSet.equals(tokenSet);
    }

    private Mono<Void> forwardAuthenticatedRequest(
            AuthRequestContext context,
            GatewayFilterChain chain,
            AuthenticatedIdentity identity) {
        if (authProperties.isRejectHeaderTokenMismatch()) {
            boolean mismatch = headerMismatch(context.inboundHeaders().user(), identity.subject())
                    || headerMismatch(context.inboundHeaders().userId(), identity.userId())
                    || authoritiesHeaderMismatch(context.inboundHeaders().authorities(), identity.authoritiesHeader());
            if (mismatch) {
                return rejectAuthentication(
                        context,
                        METRIC_AUTH_TOKEN_VALIDATION_FAILURE,
                        REJECTION_REASON_TAG,
                        "header_token_mismatch",
                        "header_token_mismatch",
                        identity.jti());
            }
        }

        ServerHttpRequest authenticatedRequest = context.request().mutate().headers(headers -> {
            headers.set(HEADER_X_USER, identity.subject());
            if (StringUtils.hasText(identity.userId())) {
                headers.set(HEADER_X_USER_ID, identity.userId());
            }
            headers.set(HEADER_X_AUTHORITIES, identity.authoritiesHeader());
        }).build();

        return chain.filter(context.exchange().mutate().request(authenticatedRequest).build());
    }

    private Mono<Void> rejectAuthentication(
            AuthRequestContext context,
            String counterName,
            String tagKey,
            String tagValue,
            String logReason,
            String jti) {
        incrementTaggedCounter(counterName, tagKey, tagValue);
        LOG.warn(LOG_JWT_AUTH_REJECTED, context.path(), logReason, jti);
        return unauthorized(context.exchange());
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
        meterRegistry.counter(counterName).increment();
    }

    private void incrementTaggedCounter(String counterName, String tagKey, String tagValue) {
        meterRegistry.counter(counterName, tagKey, tagValue).increment();
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

    private record InboundIdentityHeaders(String user, String userId, String authorities) {
    }

    private record AuthRequestContext(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            String path,
            InboundIdentityHeaders inboundHeaders) {
    }

    private record AuthenticatedIdentity(String subject, String userId, String authoritiesHeader, String jti) {
    }
}
