package com.positivity.gateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.time.TimeSource;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
    private static final String HEADER_X_PERM_BITS = "X-Perm-Bits";
    private static final String HEADER_X_PERM_VER = "X-Perm-Ver";
    private static final String HEADER_X_ROLES = "X-Roles";
    private static final String HEADER_X_USER = "X-User";
    private static final String HEADER_X_USER_ID = "X-User-Id";
    // ADR-0061 §3 location-scope passthrough (#1869). Derived from the validated token only;
    // the gateway forwards them and makes no scope decision.
    private static final String HEADER_X_LOC_FIN_BITS = "X-Loc-Fin-Bits";
    private static final String HEADER_X_LOC_OTH_BITS = "X-Loc-Oth-Bits";
    private static final String HEADER_X_LOC_SCOPE = "X-Loc-Scope";
    private static final String HEADER_X_CORRELATION_ID = "X-Correlation-Id";
    /** ADR-0062: the tenant is derived from the token, never accepted from a client header. */
    private static final String HEADER_X_TENANT_ID = "X-Tenant-Id";

    private static final String HEADER_X_TENANT_SLUG = "X-Tenant-Slug";
    private static final String JWT_HEADER_ALG = "alg";
    private static final String CLAIM_PERMISSION_VERSION = "perm_ver";
    private static final String CLAIM_TENANT_ID = "tid";
    private static final String LOGIN_PATH_SEGMENT = "/login";
    private static final Pattern TENANT_SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$");
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_LOC_FIN_BITS = "loc_fin_bits";
    private static final String CLAIM_LOC_OTH_BITS = "loc_oth_bits";
    private static final String CLAIM_LOC_SCOPE = "loc_scope";
    private static final String LOG_JWT_AUTH_REJECTED = "JWT auth rejected path={} reason={} jti={}";
    private static final String METRIC_AUTH_HEADER_STRIP_COUNT = "auth.header.strip.count";
    private static final String METRIC_INTERNAL_PATH_REFUSED_COUNT = "gateway.auth.internal_path_refused";
    private static final String METRIC_AUTH_TENANT_CLAIM_MISSING = "auth.tenant.claim.missing";
    private static final String METRIC_AUTH_LEGACY_DECODE_COUNT = "auth.legacy.decode.count";
    private static final String METRIC_AUTH_PERMISSION_CATALOG_UNKNOWN = "auth.perm.catalog.version.unknown";
    private static final String METRIC_AUTH_PERMISSION_DECODE_FAILURE = "auth.perm.decode.failure";
    private static final String METRIC_AUTH_TOKEN_VALIDATION_FAILURE = "auth.token.validation.failure";
    private static final String METRIC_AUTH_USER_IDENTITY_MISSING = "auth.user.identity.missing";
    private static final String METRIC_AUTH_TOKEN_REVOKED = "auth.token.revocation.rejected";
    private static final String METRIC_AUTH_TOKEN_REVOCATION_SKIPPED = "auth.token.revocation.skipped";
    private static final String REJECTION_REASON_TAG = "reason";
    private static final String UNSIGNED_ALG = "NONE";
    private static final String TEST_SIGNATURE_MARKER = "test-signature";
    private static final String HS256 = "HS256";
    private static final String UNKNOWN_JTI = "unknown";
    // ADR-0061 §4 / #1883: the one rejection a client can act on differently — the credential was
    // valid and is now withdrawn, so re-authenticate rather than retry. Every other auth failure
    // stays deliberately undifferentiated in the body; the reason is logged, not returned.
    private static final String ERROR_CODE_TOKEN_REVOKED = "TOKEN_REVOKED";
    private static final String ERROR_CODE_UNAUTHORIZED = "UNAUTHORIZED";
    private static final String ERROR_CODE_INTERNAL_PATH = "INTERNAL_PATH";
    private static final String ERROR_MESSAGE_INTERNAL_PATH = "Internal service paths are not exposed by the gateway";
    /**
     * Every module's service-to-service surface lives under {@code /<service>/internal/...}.
     * {@link com.positivity.gateway.filter.ApiVersionHeaderToPathFilter} runs first and injects the
     * {@code X-API-Version} segment, so the same request also reaches this filter as {@code
     * /<service>/v1/internal/...}; both spellings are refused, and the refusal therefore does not
     * depend on where the version filter happens to run in the chain.
     */
    private static final Pattern INTERNAL_SERVICE_PATH = Pattern.compile("^/[^/]+(?:/v\\d+)?/internal(?:/.*)?$");

    private static final String ERROR_MESSAGE_TOKEN_REVOKED = "Access token has been revoked";
    private static final String ERROR_MESSAGE_UNAUTHORIZED = "Authentication is required to access this resource";
    // An inbound correlation id is echoed into the error envelope, so it is accepted only in the
    // shape the platform issues (a UUID-ish token); anything else gets a fresh id.
    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    // Accelerated-run startup probe (read-only clock diagnostics). Matched exactly, never as a
    // prefix: permitting /system/** would make every future system endpoint public by default.
    private static final String SYSTEM_TIME_PATH = "/system/time";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(SecurityGatewayConfig.class);

    private final SecretKey secretKey;
    private final boolean strictJwtHeaderValidation;
    private final Set<String> allowedJwtAlgorithms;
    private final GatewayAuthProperties authProperties;
    private final MeterRegistry meterRegistry;
    private final TokenRevocationChecker revocationChecker;

    @Autowired
    public SecurityGatewayConfig(
            @Value("${security.jwt.secret}") @NonNull String jwtSecret,
            @Value("${pos.gateway.security.strict-jwt-header-validation:false}") boolean strictJwtHeaderValidation,
            @Value("${pos.gateway.security.allowed-jwt-algorithms:HS256}") String allowedJwtAlgorithmsCsv,
            @NonNull GatewayAuthProperties authProperties,
            @NonNull MeterRegistry meterRegistry,
            @NonNull TokenRevocationChecker revocationChecker) {
        this(
                jwtSecret,
                strictJwtHeaderValidation,
                parseAllowedJwtAlgorithms(allowedJwtAlgorithmsCsv),
                authProperties,
                meterRegistry,
                revocationChecker);
    }

    /**
     * Claim-handling constructor: no revocation check. Kept so the validation, bitset-decode and
     * header trust-boundary tests exercise exactly the code path they are about, unchanged.
     */
    SecurityGatewayConfig(
            @NonNull String jwtSecret,
            boolean strictJwtHeaderValidation,
            Set<String> allowedJwtAlgorithms,
            @NonNull GatewayAuthProperties authProperties,
            @NonNull MeterRegistry meterRegistry) {
        this(
                jwtSecret,
                strictJwtHeaderValidation,
                allowedJwtAlgorithms,
                authProperties,
                meterRegistry,
                TokenRevocationChecker.DISABLED);
    }

    SecurityGatewayConfig(
            @NonNull String jwtSecret,
            boolean strictJwtHeaderValidation,
            Set<String> allowedJwtAlgorithms,
            @NonNull GatewayAuthProperties authProperties,
            @NonNull MeterRegistry meterRegistry,
            @NonNull TokenRevocationChecker revocationChecker) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.strictJwtHeaderValidation = strictJwtHeaderValidation;
        this.allowedJwtAlgorithms = normalizeAllowedJwtAlgorithms(allowedJwtAlgorithms);
        this.authProperties = authProperties;
        this.meterRegistry = meterRegistry;
        this.revocationChecker = revocationChecker;
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
                        // HEAD is required by the TUS resumable-upload protocol (offset probe on resume).
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD")
                        .allowedHeaders("*")
                        // Response headers browser JS must be able to read. A wildcard is not
                        // usable here because allowCredentials(true) disables '*' expansion.
                        // Location + Upload-* / Tus-* are required by TUS upload clients.
                        .exposedHeaders(
                                "Location",
                                "Upload-Offset",
                                "Upload-Length",
                                "Upload-Expires",
                                "Tus-Resumable",
                                "Tus-Version",
                                "Tus-Max-Size",
                                "Tus-Extension")
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
        if (isInternalServicePath(context.path())) {
            // Service-to-service surfaces (pos-tenant's /internal/v1/tenants, guarded by a shared
            // secret) are reachable only inside the mesh; a generic /{service}/** route must not
            // carry them out to the public edge, whatever credential the caller holds.
            LOG.warn("Refusing an internal service path at the gateway; path={}", context.path());
            incrementCounter(METRIC_INTERNAL_PATH_REFUSED_COUNT);
            return reject(
                    context.exchange(), HttpStatus.FORBIDDEN, ERROR_CODE_INTERNAL_PATH, ERROR_MESSAGE_INTERNAL_PATH);
        }
        if (isPublicPath(context.path())) {
            return chain.filter(withTenantSlugFromHost(context));
        }

        Optional<String> token = extractBearerToken(context.request());
        if (token.isEmpty()) {
            LOG.warn("Missing Authorization header; path={}", context.path());
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

        return forwardUnlessRevoked(context, chain, identity.get());
    }

    /**
     * Consults the shared revocation key space before forwarding (#1883, ADR-0061 §4).
     *
     * <p>Signature, issuer, audience and expiry are all the gateway used to check, so a token
     * revoked by logout, {@code revokeAllTokensForUser}, refresh rotation or a reach-narrowing
     * staffing change kept passing here until its {@code exp} — and every downstream service
     * trusts the headers this filter writes. The lookup is one {@code EXISTS} against the keys
     * {@code TokenRevocationManager} writes; it never reads pos-security-service's {@code
     * jwt_token} table, which is that module's own schema.
     *
     * <p>A token with no {@code jti} cannot be looked up and is forwarded. That is not a bypass an
     * attacker can reach for — the claim set is fixed by the issuer's signature, and only legacy
     * pre-{@code perm_ver} tokens lack the claim — but it is a real hole in coverage while such
     * tokens are still accepted, so it is counted rather than passed over silently.
     */
    private Mono<Void> forwardUnlessRevoked(
            AuthRequestContext context, GatewayFilterChain chain, AuthenticatedIdentity identity) {
        if (UNKNOWN_JTI.equals(identity.jti())) {
            incrementTaggedCounter(METRIC_AUTH_TOKEN_REVOCATION_SKIPPED, REJECTION_REASON_TAG, "no_jti");
            return forwardAuthenticatedRequest(context, chain, identity);
        }

        return revocationChecker.isRevoked(identity.jti()).flatMap(revoked -> {
            if (Boolean.TRUE.equals(revoked)) {
                incrementCounter(METRIC_AUTH_TOKEN_REVOKED);
                LOG.warn(LOG_JWT_AUTH_REJECTED, context.path(), "revoked", identity.jti());
                return unauthorized(context.exchange(), ERROR_CODE_TOKEN_REVOKED, ERROR_MESSAGE_TOKEN_REVOKED);
            }
            return forwardAuthenticatedRequest(context, chain, identity);
        });
    }

    private AuthRequestContext createAuthRequestContext(ServerWebExchange exchange) {
        ServerHttpRequest incomingRequest = exchange.getRequest();
        URI uri = incomingRequest.getURI();
        InboundIdentityHeaders inboundHeaders = new InboundIdentityHeaders(
                incomingRequest.getHeaders().getFirst(HEADER_X_USER),
                incomingRequest.getHeaders().getFirst(HEADER_X_USER_ID),
                incomingRequest.getHeaders().getFirst(HEADER_X_AUTHORITIES),
                incomingRequest.getHeaders().getFirst(HEADER_X_PERM_BITS),
                incomingRequest.getHeaders().getFirst(HEADER_X_LOC_FIN_BITS),
                incomingRequest.getHeaders().getFirst(HEADER_X_LOC_OTH_BITS),
                incomingRequest.getHeaders().getFirst(HEADER_X_LOC_SCOPE));

        ServerHttpRequest strippedRequest = incomingRequest
                .mutate()
                .headers(headers -> {
                    if (authProperties.isStripInboundIdentityHeaders()) {
                        headers.remove(HEADER_X_USER);
                        headers.remove(HEADER_X_USER_ID);
                        headers.remove(HEADER_X_AUTHORITIES);
                        headers.remove(HEADER_X_PERM_BITS);
                        headers.remove(HEADER_X_PERM_VER);
                        headers.remove(HEADER_X_ROLES);
                        headers.remove(HEADER_X_LOC_FIN_BITS);
                        headers.remove(HEADER_X_LOC_OTH_BITS);
                        headers.remove(HEADER_X_LOC_SCOPE);
                        headers.remove(HEADER_X_TENANT_ID);
                        headers.remove(HEADER_X_TENANT_SLUG);
                        incrementCounter(METRIC_AUTH_HEADER_STRIP_COUNT);
                    }
                })
                .build();

        ServerWebExchange strippedExchange =
                exchange.mutate().request(strippedRequest).build();
        return new AuthRequestContext(strippedExchange, strippedRequest, uri.getPath(), inboundHeaders);
    }

    private Optional<String> extractBearerToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return Optional.of(authHeader.substring(7));
    }

    /**
     * iss/aud claims are now emitted by pos-security-service (since
     * AUTH-HARDENING review).
     */
    private Optional<Claims> parseVerifiedClaims(String token, AuthRequestContext context) {
        try {
            Jws<Claims> jwsClaims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer("pos-security-service")
                    .requireAudience("api-gateway")
                    .build()
                    .parseSignedClaims(token);
            return Optional.of(jwsClaims.getPayload());
        } catch (JwtException ex) {
            String reason = tokenValidationReason(ex);
            LOG.warn("JWT parse failed path={} reason={} detail={}", context.path(), reason, ex.getMessage());
            rejectAuthentication(
                    context, METRIC_AUTH_TOKEN_VALIDATION_FAILURE, REJECTION_REASON_TAG, reason, reason, UNKNOWN_JTI);
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

        Optional<String> tenantId = resolveTenantId(claims, context, jti);
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> legacyAuthoritiesHeader = resolveLegacyAuthoritiesHeader(claims, context, jti);
        String rolesHeader = resolveRolesHeader(claims);
        if (legacyAuthoritiesHeader.isPresent()) {
            return Optional.of(new AuthenticatedIdentity(
                    subject,
                    claims.get("uid", String.class),
                    "", // no perm_bits for legacy tokens
                    legacyAuthoritiesHeader.get(), // CSV from legacy authorities claim
                    rolesHeader,
                    LocationScopeHeaders.ABSENT, // legacy tokens predate the scope claims
                    jti,
                    tenantId.get().isEmpty() ? null : tenantId.get()));
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

        Optional<LocationScopeHeaders> locationScopeHeaders = resolveLocationScopeHeaders(claims, context, jti);
        if (locationScopeHeaders.isEmpty()) {
            return Optional.empty();
        }

        String permBits = claims.get("perm_bits", String.class);
        return Optional.of(new AuthenticatedIdentity(
                subject,
                claims.get("uid", String.class),
                permBits != null ? permBits : "",
                "", // no legacy CSV for new tokens
                rolesHeader,
                locationScopeHeaders.get(),
                jti,
                tenantId.get().isEmpty() ? null : tenantId.get()));
    }

    /**
     * The {@code tid} claim (ADR-0062 §3, ADR-0040 §2 amendment): the tenant every downstream row
     * is read and written under, forwarded verbatim as {@code X-Tenant-Id}. A malformed value is
     * rejected like any other bad claim; an absent one is tolerated only while pre-WS2b tokens are
     * still in circulation (the issuer stamps it on every token now) and yields an empty string, so
     * downstream falls back to its transitional default rather than a forged header.
     */
    private Optional<String> resolveTenantId(Claims claims, AuthRequestContext context, String jti) {
        Object raw = claims.get(CLAIM_TENANT_ID);
        if (raw == null) {
            incrementTaggedCounter(METRIC_AUTH_TENANT_CLAIM_MISSING, REJECTION_REASON_TAG, "no_tid");
            return Optional.of("");
        }
        try {
            return Optional.of(UUID.fromString(String.valueOf(raw)).toString());
        } catch (IllegalArgumentException ex) {
            rejectAuthentication(
                    context,
                    METRIC_AUTH_TOKEN_VALIDATION_FAILURE,
                    REJECTION_REASON_TAG,
                    "malformed_tid",
                    "malformed_tid",
                    jti);
            return Optional.empty();
        }
    }

    /**
     * On the public login route, derive {@code X-Tenant-Slug} from the {@code Host} header when
     * {@code auth.tenant-host-suffix} is configured (ADR-0062 §3, plan WS2b): {@code
     * acme.durionpos.org} becomes {@code acme}. Any inbound copy of the header was already stripped;
     * a host that does not match the suffix, or a label that is not a valid slug, forwards nothing
     * and leaves resolution to the login body.
     */
    private ServerWebExchange withTenantSlugFromHost(AuthRequestContext context) {
        if (!isLoginPath(context.path())) {
            return context.exchange();
        }
        Optional<String> slug = tenantSlugFromHost(context.request());
        if (slug.isEmpty()) {
            return context.exchange();
        }
        ServerHttpRequest withSlug = context.request()
                .mutate()
                .headers(headers -> headers.set(HEADER_X_TENANT_SLUG, slug.get()))
                .build();
        return context.exchange().mutate().request(withSlug).build();
    }

    private Optional<String> tenantSlugFromHost(ServerHttpRequest request) {
        String suffix = authProperties.getTenantHostSuffix();
        if (!StringUtils.hasText(suffix)) {
            return Optional.empty();
        }
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        if (!StringUtils.hasText(host)) {
            host = request.getURI().getHost();
        }
        if (!StringUtils.hasText(host)) {
            return Optional.empty();
        }
        int colon = host.indexOf(':');
        String bareHost = (colon > 0 ? host.substring(0, colon) : host).toLowerCase(java.util.Locale.ROOT);
        String normalizedSuffix = suffix.trim().toLowerCase(java.util.Locale.ROOT);
        if (!bareHost.endsWith(normalizedSuffix) || bareHost.length() <= normalizedSuffix.length()) {
            return Optional.empty();
        }
        String prefix = bareHost.substring(0, bareHost.length() - normalizedSuffix.length());
        // The tenant is the first label; anything between it and the suffix (acme.dev.<suffix>)
        // is environment routing, not tenant identity.
        int dot = prefix.indexOf('.');
        String label = dot < 0 ? prefix : prefix.substring(0, dot);
        return TENANT_SLUG.matcher(label).matches() ? Optional.of(label) : Optional.empty();
    }

    /** The credential login route only: the one public auth path whose body names a tenant. */
    private boolean isLoginPath(String path) {
        return isAuthPath(path) && path.endsWith(LOGIN_PATH_SEGMENT);
    }

    private boolean isAuthPath(String path) {
        return isPathMatch(path, authProperties.getAuthPathRoot(), authProperties.getAuthPathPrefix())
                || isPathMatch(
                        path, authProperties.getStrippedAuthPathRoot(), authProperties.getStrippedAuthPathPrefix());
    }

    /**
     * Derives the three location-scope headers from the validated token (ADR-0061 §2–§3, #1869).
     *
     * <p>{@code loc_fin_bits} / {@code loc_oth_bits} are forwarded verbatim — they are already
     * Base64URL bitsets over the same indexes as {@code perm_bits} — and are forwarded even when
     * empty, because "bitsets present" is itself a signal. {@code loc_scope} is a JSON object; it
     * is forwarded as the Base64URL of its compact JSON so the header is safe and lossless, and it
     * is <b>omitted</b> whenever the claim is absent: "bits present, scope absent" is the issuer's
     * fail-closed signal and must reach the service unchanged. Nothing is ever synthesised.
     *
     * <p>A token carrying none of the claims (pre-rollout) yields {@link LocationScopeHeaders#ABSENT}.
     * A claim of the wrong JSON type is an issuer defect and rejects the token, the same way a
     * malformed {@code perm_bits} does; the gateway does not otherwise inspect the values.
     */
    private Optional<LocationScopeHeaders> resolveLocationScopeHeaders(
            Claims claims, AuthRequestContext context, String jti) {
        Optional<String> finBits = stringClaim(claims, CLAIM_LOC_FIN_BITS);
        Optional<String> othBits = stringClaim(claims, CLAIM_LOC_OTH_BITS);
        Object rawScope = claims.get(CLAIM_LOC_SCOPE);

        boolean finMalformed = claims.get(CLAIM_LOC_FIN_BITS) != null && finBits.isEmpty();
        boolean othMalformed = claims.get(CLAIM_LOC_OTH_BITS) != null && othBits.isEmpty();
        boolean scopeMalformed = rawScope != null && !(rawScope instanceof Map<?, ?>);
        if (finMalformed || othMalformed || scopeMalformed) {
            rejectAuthentication(
                    context,
                    METRIC_AUTH_PERMISSION_DECODE_FAILURE,
                    REJECTION_REASON_TAG,
                    "malformed_loc_claim",
                    "malformed_loc_claim",
                    jti);
            return Optional.empty();
        }

        String scopeHeader = null;
        if (rawScope != null) {
            try {
                String compactJson = OBJECT_MAPPER.writeValueAsString(rawScope);
                scopeHeader = Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(compactJson.getBytes(StandardCharsets.UTF_8));
            } catch (JsonProcessingException ex) {
                rejectAuthentication(
                        context,
                        METRIC_AUTH_PERMISSION_DECODE_FAILURE,
                        REJECTION_REASON_TAG,
                        "malformed_loc_claim",
                        "malformed_loc_scope",
                        jti);
                return Optional.empty();
            }
        }
        return Optional.of(new LocationScopeHeaders(finBits.orElse(null), othBits.orElse(null), scopeHeader));
    }

    private static Optional<String> stringClaim(Claims claims, String name) {
        Object value = claims.get(name);
        return value instanceof String text ? Optional.of(text) : Optional.empty();
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

    private String resolveRolesHeader(Claims claims) {
        Object rolesClaim = claims.get(CLAIM_ROLES);
        if (!(rolesClaim instanceof List<?> rolesList)) {
            return "";
        }

        return rolesList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(this::normalizeRoleClaim)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining(","));
    }

    /**
     * Compares the X-Authorities header value with the authorities derived from the
     * token
     * in a way that is insensitive to ordering and extraneous whitespace.
     */
    private boolean authoritiesHeaderMismatch(String headerAuthorities, String tokenAuthorities) {
        if (!StringUtils.hasText(headerAuthorities)) {
            // Only enforce mismatch checks when the inbound spoofable header is actually
            // present.
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
            AuthRequestContext context, GatewayFilterChain chain, AuthenticatedIdentity identity) {
        if (authProperties.isRejectHeaderTokenMismatch()) {
            boolean mismatch = headerMismatch(context.inboundHeaders().user(), identity.subject())
                    || headerMismatch(context.inboundHeaders().userId(), identity.userId())
                    || authoritiesHeaderMismatch(
                            context.inboundHeaders().authorities(), identity.legacyAuthoritiesHeader())
                    || headerMismatch(context.inboundHeaders().permBits(), identity.permBitsHeader())
                    || headerMismatch(
                            context.inboundHeaders().locFinBits(),
                            identity.locationScope().finBits())
                    || headerMismatch(
                            context.inboundHeaders().locOthBits(),
                            identity.locationScope().othBits())
                    || headerMismatch(
                            context.inboundHeaders().locScope(),
                            identity.locationScope().scope());
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

        ServerHttpRequest authenticatedRequest = context.request()
                .mutate()
                .headers(headers -> {
                    headers.set(HEADER_X_USER, identity.subject());
                    if (StringUtils.hasText(identity.userId())) {
                        headers.set(HEADER_X_USER_ID, identity.userId());
                    }
                    if (StringUtils.hasText(identity.permBitsHeader())) {
                        headers.set(HEADER_X_PERM_BITS, identity.permBitsHeader());
                        headers.set(HEADER_X_PERM_VER, String.valueOf(GatewayPermissionCatalog.CATALOG_VERSION));
                        headers.remove(HEADER_X_AUTHORITIES);
                    } else if (StringUtils.hasText(identity.legacyAuthoritiesHeader())) {
                        headers.set(HEADER_X_AUTHORITIES, identity.legacyAuthoritiesHeader());
                        headers.remove(HEADER_X_PERM_BITS);
                        headers.remove(HEADER_X_PERM_VER);
                    }
                    if (StringUtils.hasText(identity.rolesHeader())) {
                        headers.set(HEADER_X_ROLES, identity.rolesHeader());
                    } else {
                        headers.remove(HEADER_X_ROLES);
                    }
                    // ADR-0062 §3: the tenant travels only as the validated tid claim; an absent
                    // claim removes the header so no inbound copy can survive.
                    setOrRemove(headers, HEADER_X_TENANT_ID, identity.tenantId());
                    // Present-but-empty bitsets are forwarded as empty headers on purpose; an
                    // absent claim removes the header so no inbound copy can survive.
                    setOrRemove(
                            headers,
                            HEADER_X_LOC_FIN_BITS,
                            identity.locationScope().finBits());
                    setOrRemove(
                            headers,
                            HEADER_X_LOC_OTH_BITS,
                            identity.locationScope().othBits());
                    setOrRemove(
                            headers,
                            HEADER_X_LOC_SCOPE,
                            identity.locationScope().scope());
                })
                .build();

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "Forwarding authenticated request path={} user={} userId={} roles={} permBits={} legacyAuthorities={} locBits={} locScope={}",
                    context.path(),
                    identity.subject(),
                    identity.userId(),
                    countCsvEntries(identity.rolesHeader()),
                    StringUtils.hasText(identity.permBitsHeader()) ? "present" : "absent",
                    countCsvEntries(identity.legacyAuthoritiesHeader()),
                    identity.locationScope().finBits() != null ? "present" : "absent",
                    identity.locationScope().scope() != null ? "present" : "absent");
        }

        return chain.filter(
                context.exchange().mutate().request(authenticatedRequest).build());
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

    private static boolean isInternalServicePath(String path) {
        return path != null && INTERNAL_SERVICE_PATH.matcher(path).matches();
    }

    private boolean isPublicPath(String path) {
        return SYSTEM_TIME_PATH.equals(path)
                || path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-resources")
                || path.startsWith("/eureka")
                || ARTIFACT_DOWNLOAD_PATH.matcher(path).matches()
                || isPathMatch(path, authProperties.getAuthPathRoot(), authProperties.getAuthPathPrefix())
                || isPathMatch(
                        path, authProperties.getStrippedAuthPathRoot(), authProperties.getStrippedAuthPathPrefix());
    }

    // Public, token-authorized invoice artifact download (browser direct-download link cannot carry
    // a JWT). Authorization is enforced by the signed token in pos-invoice, not at the gateway.
    private static final java.util.regex.Pattern ARTIFACT_DOWNLOAD_PATH =
            java.util.regex.Pattern.compile("^/invoice/v1/invoices/[^/]+/artifacts/[^/]+/download$");

    private static boolean isPathMatch(String path, String root, String prefix) {
        return (StringUtils.hasText(root) && path.equals(root))
                || (StringUtils.hasText(prefix) && path.startsWith(prefix));
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

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        return unauthorized(exchange, ERROR_CODE_UNAUTHORIZED, ERROR_MESSAGE_UNAUTHORIZED);
    }

    /**
     * Completes the exchange with 401 and the platform {@code ApiError} envelope
     * ({@code docs/ERROR_ENVELOPE.md}).
     *
     * <p>Rejections used to be a bare 401 with no body, which left the gateway as the one place in
     * the platform returning an un-parseable error. The body says only which of two things
     * happened — the credential is unusable, or it was revoked — because the specific reason
     * (bad signature, unknown {@code perm_ver}, malformed claim) is diagnostic for us and a probing
     * oracle for anyone else; it is logged and counted instead.
     */
    private static Mono<Void> unauthorized(ServerWebExchange exchange, String code, String message) {
        return reject(exchange, HttpStatus.UNAUTHORIZED, code, message);
    }

    /** Completes the exchange with {@code status} and the platform {@code ApiError} envelope. */
    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        String correlationId = resolveCorrelationId(exchange.getRequest());
        response.getHeaders().set(HEADER_X_CORRELATION_ID, correlationId);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("message", message);
        envelope.put("status", status.value());
        envelope.put("timestamp", TimeSource.instant().toString());
        envelope.put("correlationId", correlationId);

        byte[] body;
        try {
            body = OBJECT_MAPPER.writeValueAsBytes(envelope);
        } catch (JsonProcessingException ex) {
            // Unreachable for a map of strings and an int; a 401 without a body still beats a 500.
            LOG.warn("Failed to serialize {} error envelope; responding without a body", status.value(), ex);
            return response.setComplete();
        }

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * Echoes the caller's correlation id when it is one, else mints one.
     *
     * <p>Trimmed before validating, matching {@code GlobalApiExceptionHandler} in pos-web-common:
     * a padded header is the same id, and minting a fresh one for it would silently break the
     * cross-service correlation the header exists for. Validated because the value is echoed into
     * the response body, and only the shape the platform issues is accepted.
     */
    private static String resolveCorrelationId(ServerHttpRequest request) {
        String inbound = request.getHeaders().getFirst(HEADER_X_CORRELATION_ID);
        if (StringUtils.hasText(inbound)) {
            String trimmed = inbound.trim();
            if (CORRELATION_ID_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String normalizeRoleClaim(String role) {
        if (!StringUtils.hasText(role)) {
            return "";
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper.replaceFirst("^ROLE", "");
    }

    private static int countCsvEntries(String csv) {
        if (!StringUtils.hasText(csv)) {
            return 0;
        }
        return (int) Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .count();
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

    private static void setOrRemove(HttpHeaders headers, String name, @Nullable String value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.set(name, value);
        }
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

    private record InboundIdentityHeaders(
            String user,
            String userId,
            String authorities,
            String permBits,
            String locFinBits,
            String locOthBits,
            String locScope) {}

    /**
     * The downstream location-scope headers derived from one token. {@code null} means the claim
     * was absent and the header must not be set; an empty string is a present, empty bitset.
     *
     * @param finBits verbatim {@code loc_fin_bits}
     * @param othBits verbatim {@code loc_oth_bits}
     * @param scope Base64URL of the compact JSON of {@code loc_scope}
     */
    private record LocationScopeHeaders(
            @Nullable String finBits,
            @Nullable String othBits,
            @Nullable String scope) {
        static final LocationScopeHeaders ABSENT = new LocationScopeHeaders(null, null, null);
    }

    private record AuthRequestContext(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            String path,
            InboundIdentityHeaders inboundHeaders) {}

    /**
     * @param tenantId the validated {@code tid} claim (ADR-0062 §3), forwarded as
     *     {@code X-Tenant-Id}; {@code null} only for a legacy token that predates the claim
     */
    private record AuthenticatedIdentity(
            String subject,
            String userId,
            String permBitsHeader,
            String legacyAuthoritiesHeader,
            String rolesHeader,
            LocationScopeHeaders locationScope,
            String jti,
            @Nullable String tenantId) {}
}
