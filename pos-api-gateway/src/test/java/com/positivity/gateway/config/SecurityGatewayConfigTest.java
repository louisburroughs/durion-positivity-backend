package com.positivity.gateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.config.CorsRegistry;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for gateway JWT validation, permission decoding, and
 * header trust-boundary behavior.
 *
 * Issue: PERM-006, PERM-007, PERM-008, PERM-009, PERM-010, PERM-011, AUTH-007.
 */
class SecurityGatewayConfigTest {

    private static final String TEST_SECRET = "test-jwt-secret-key-01234567890123456789";
    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    // ── token / bit helpers ──────────────────────────────────────────────────

    /** Builds a valid signed access token carrying PERM claims. */
    private static String buildToken(String sub, String uid, String permBits, int permVer) {
        return Jwts.builder()
                .subject(sub)
                .claim("uid", uid)
                .claim("perm_bits", permBits)
                .claim("perm_ver", permVer)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /** Builds a signed access token that is already expired. */
    private static String buildExpiredToken(String sub, String uid) {
        return Jwts.builder()
                .subject(sub)
                .claim("uid", uid)
                .claim("perm_bits", "")
                .claim("perm_ver", 1)
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /** Builds a signed access token with perm_ver but no perm_bits claim. */
    private static String buildTokenWithoutPermBits(String sub, String uid, int permVer) {
        return Jwts.builder()
                .subject(sub)
                .claim("uid", uid)
                .claim("perm_ver", permVer)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /** Builds a signed legacy access token with authorities but no PERM claims. */
    private static String buildLegacyAuthoritiesToken(String sub, String uid, String... authorities) {
        return Jwts.builder()
                .subject(sub)
                .claim("uid", uid)
                .claim("authorities", Arrays.asList(authorities))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /** Encodes bit indexes into Base64URL-no-padding perm_bits. */
    private static String encodePermBits(int... bitIndexes) {
        BitSet bs = new BitSet();
        for (int idx : bitIndexes) {
            bs.set(idx);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bs.toByteArray());
    }

    /** Builds a signed token with no {@code sub} claim (uid present). */
    private static String buildTokenWithoutSub(String uid) {
        return Jwts.builder()
                .claim("uid", uid)
                .claim("perm_bits", "")
                .claim("perm_ver", 1)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /** Builds a signed token with no {@code uid} claim (sub present). */
    private static String buildTokenWithoutUidClaim(String sub, String permBits, int permVer) {
        return Jwts.builder()
                .subject(sub)
                .claim("perm_bits", permBits)
                .claim("perm_ver", permVer)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /**
     * Builds a canonical AUTH-005 access token with the full claim set emitted by
     * {@code JwtServiceImpl.generateTokenPair()}: {@code jti}, {@code sub},
     * {@code uid}, {@code username}, {@code perm_bits}, {@code perm_ver}, {@code iat},
     * and optionally {@code personId}.
     *
     * <p>Does NOT include the legacy {@code authorities} claim.
     */
    private static String buildCanonicalToken(
            String sub, String uid, String personId, String permBits, int permVer) {
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(sub)
                .claim("uid", uid)
                .claim("username", sub)
                .claim("perm_bits", permBits)
                .claim("perm_ver", permVer)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY);
        if (personId != null) {
            builder = builder.claim("personId", personId);
        }
        return builder.compact();
    }

    // ── PERM-006 / PERM-007 — local JWT validation & bitset decode ───────────

    /**
     * Valid token with people permission bits is forwarded with decoded
     * authorities.
     */
    @Test
    void validToken_withPermBits_returns200_withDecodedAuthorities() {
        // bits 116 = people:employee:view, 117 = people:employee:create
        String permBits = encodePermBits(116, 117);
        String token = buildToken("alice", "u1", permBits, 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        String authorities = downstreamHeaders.get().getFirst("X-Authorities");
        assertThat(authorities)
                .contains("PERM_people:employee:view")
                .contains("PERM_people:employee:create");
    }

    /** Token signed with a different key is rejected as unauthorized. */
    @Test
    void invalidSignature_returns401() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "wrong-key-that-does-not-match-0000000000000000".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("alice")
                .claim("uid", "u1")
                .claim("perm_bits", "")
                .claim("perm_ver", 1)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(wrongKey)
                .compact();

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Expired token is rejected as unauthorized. */
    @Test
    void expiredToken_returns401() {
        String token = buildExpiredToken("alice", "u1");

        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setTokenIdentityRequired(true);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                props,
                new SimpleMeterRegistry())
                .authFilter();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PERM-009 — auth.token-identity-required feature flag ─────────────────

    /**
     * Missing perm_bits is rejected when token identity is required and perm_ver is
     * valid.
     */
    @Test
    void missingPermBits_whenTokenIdentityRequired_returns401() {
        String token = buildTokenWithoutPermBits("alice", "u1", 1);

        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setTokenIdentityRequired(true);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                props,
                new SimpleMeterRegistry())
                .authFilter();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PERM-007 — catalog version + bitset validation ───────────────────────

    /** Unknown permission catalog version is rejected as unauthorized. */
    @Test
    void unknownPermVer_returns401() {
        String permBits = encodePermBits(116);
        String token = buildToken("alice", "u1", permBits, 99);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Malformed perm_bits claim is rejected as unauthorized. */
    @Test
    void malformedBase64PermBits_returns401() {
        String token = buildToken("alice", "u1", "!!!NOT_BASE64!!!", 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PERM-008 — inbound identity header stripping ─────────────────────────

    /**
     * Spoofed inbound authorities are stripped and replaced with token-derived
     * authorities.
     */
    @Test
    void spoofedXAuthoritiesHeader_isStripped() {
        String permBits = encodePermBits(116);
        String token = buildToken("alice", "u1", permBits, 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Authorities", "ROLE_SUPER_ADMIN")
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        String authorities = downstreamHeaders.get().getFirst("X-Authorities");
        assertThat(authorities)
                .doesNotContain("ROLE_SUPER_ADMIN")
                .contains("PERM_people:employee:view");
    }

    /** Spoofed inbound user header is stripped and replaced by token subject. */
    @Test
    void spoofedXUserHeader_isStripped() {
        String permBits = encodePermBits(116);
        String token = buildToken("alice", "u1", permBits, 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User", "hacker")
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("alice");
        assertThat(downstreamHeaders.get().getFirst("X-User")).doesNotContain("hacker");
    }

    /** Public paths still strip inbound identity headers before forwarding. */
    @Test
    void publicPath_passesThrough_withHeaderStripping() {
        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health")
                        .header("X-User", "spoofed-user")
                        .header("X-Authorities", "ROLE_SUPER_ADMIN")
                        .build());

        filter.filter(exchange, chain).block();

        // Request passes through (not 401) but identity headers must be stripped.
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        assertThat(downstreamHeaders.get().get("X-User")).isNullOrEmpty();
        assertThat(downstreamHeaders.get().get("X-Authorities")).isNullOrEmpty();
    }

    /**
     * Legacy token authorities claim is accepted when perm_ver and perm_bits are
     * absent.
     */
    @Test
    void legacyToken_withAuthoritiesClaim_returns200_withLegacyAuthorities() {
        String token = buildLegacyAuthoritiesToken("alice", "u1", "PERM_accounting:je:view");

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        assertThat(downstreamHeaders.get().getFirst("X-Authorities"))
                .isEqualTo("PERM_accounting:je:view");
    }

    // ── PERM-007 — zero / full permission volume ─────────────────────────────

    /**
     * Empty perm_bits is accepted and yields an empty/absent authorities header.
     */
    @Test
    void tokenWithZeroPermissions_returns200_withEmptyAuthorities() {
        String token = buildToken("alice", "u1", "", 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        String authorities = downstreamHeaders.get() == null
                ? null
                : downstreamHeaders.get().getFirst("X-Authorities");
        assertThat(authorities).isNullOrEmpty();
    }

    /** Full catalog perm_bits is accepted and yields 215 PERM authorities. */
    @Test
    void tokenWithAllPermissions_returns200_with215Authorities() {
        int[] allBits = new int[215];
        for (int i = 0; i < 215; i++) {
            allBits[i] = i;
        }
        String permBits = encodePermBits(allBits);
        String token = buildToken("alice", "u1", permBits, 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get()).isNotNull();
        String authorities = downstreamHeaders.get().getFirst("X-Authorities");
        assertThat(authorities).isNotNull();
        long permCount = Arrays.stream(authorities.split(","))
                .filter(a -> a.startsWith("PERM_"))
                .count();
        assertThat(permCount).isEqualTo(215);
    }

    // ── PERM-006 — missing Authorization header ───────────────────────────────

    /** Missing Authorization header is rejected as unauthorized. */
    @Test
    void missingAuthorizationHeader_returns401() {
        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET,
                false,
                Set.of("HS256"),
                new GatewayAuthProperties(),
                new SimpleMeterRegistry())
                .authFilter();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees").build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── CORS configurer bean ──────────────────────────────────────────────────

    /**
     * corsConfigurer bean is non-null and addCorsMappings executes without error.
     */
    @Test
    void corsConfigurer_addCorsMappings_isCallable() {
        SecurityGatewayConfig config = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry());
        var configurer = config.corsConfigurer();
        assertThat(configurer).isNotNull();
        configurer.addCorsMappings(new CorsRegistry());
    }

    // ── PERM-009 — rejectHeaderTokenMismatch feature flag ────────────────────

    /**
     * Inbound X-User conflicts with token subject → 401 when mismatch-rejection is
     * on.
     */
    @Test
    void rejectHeaderTokenMismatch_conflictingXUser_returns401() {
        String permBits = encodePermBits(116);
        String token = buildToken("alice", "u1", permBits, 1);

        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setRejectHeaderTokenMismatch(true);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User", "mallory")
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Inbound X-User matches token subject → forwarded when mismatch-rejection is
     * on.
     */
    @Test
    void rejectHeaderTokenMismatch_matchingXUser_returns200() {
        String permBits = encodePermBits(116);
        String token = buildToken("alice", "u1", permBits, 1);

        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setRejectHeaderTokenMismatch(true);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User", "alice")
                        .header("X-User-Id", "u1")
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("alice");
    }

    // ── PERM-008 — stripInboundIdentityHeaders=false ─────────────────────────

    /**
     * When stripping is disabled, identity headers pass through on public paths.
     */
    @Test
    void stripInboundIdentityHeaders_disabled_publicPath_headersPassThrough() {
        GatewayAuthProperties props = new GatewayAuthProperties();
        props.setStripInboundIdentityHeaders(false);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health")
                        .header("X-User", "spoofed-user")
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("spoofed-user");
    }

    // ── PERM-006 — missing sub claim ──────────────────────────────────────────

    /** Token without sub claim is rejected as unauthorized. */
    @Test
    void missingSubClaim_returns401() {
        String token = buildTokenWithoutSub("u1");

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PERM-007 — uid claim absent ───────────────────────────────────────────

    /** Token without uid claim does not set X-User-Id header downstream. */
    @Test
    void tokenWithoutUidClaim_returns200_withoutUserIdHeader() {
        String permBits = encodePermBits(116);
        String token = buildTokenWithoutUidClaim("alice", permBits, 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("alice");
        assertThat(downstreamHeaders.get().get("X-User-Id")).isNullOrEmpty();
    }

    // ── PERM-009 — missing perm_bits when tokenIdentityRequired=false ─────────

    /**
     * Missing perm_bits with perm_ver=1 and tokenIdentityRequired=false yields
     * empty authorities.
     */
    @Test
    void missingPermBits_whenTokenIdentityNotRequired_returns200_withEmptyAuthorities() {
        String token = buildTokenWithoutPermBits("alice", "u1", 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get().getFirst("X-Authorities")).isNullOrEmpty();
    }

    // ── PERM-007 — legacy token edge cases ────────────────────────────────────

    /** Legacy token whose authorities claim is a String (not List) is rejected. */
    @Test
    void legacyToken_withNonListAuthoritiesClaim_returns401() {
        String token = Jwts.builder()
                .subject("alice")
                .claim("uid", "u1")
                .claim("authorities", "PERM_accounting:je:view")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Legacy token with an empty authorities list is rejected. */
    @Test
    void legacyToken_withEmptyAuthoritiesList_returns401() {
        String token = Jwts.builder()
                .subject("alice")
                .claim("uid", "u1")
                .claim("authorities", List.of())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PERM-006 — strict JWT pre-validation (strictJwtHeaderValidation=true) ─

    /**
     * Strict validation rejects tokens where alg=none appears in the JWT header.
     */
    @Test
    void strictValidation_algNone_returns401() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"alice\",\"perm_bits\":\"\",\"perm_ver\":1,\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String unsignedToken = header + "." + payload + ".";

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, true, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unsignedToken)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Strict validation rejects tokens whose signature part contains the
     * test-signature marker.
     */
    @Test
    void strictValidation_testSignatureMarker_returns401() {
        String realToken = buildToken("alice", "u1", "", 1);
        String[] parts = realToken.split("\\.");
        String syntheticToken = parts[0] + "." + parts[1] + ".test-signature-tampered";

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, true, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + syntheticToken)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Strict validation rejects a HS256 token when only HS384 is allowed. */
    @Test
    void strictValidation_disallowedAlgorithm_returns401() {
        String token = buildToken("alice", "u1", "", 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, true, Set.of("HS384"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Strict validation passes a valid HS256 token when HS256 is allowed. */
    @Test
    void strictValidation_validHS256Token_returns200() {
        String permBits = encodePermBits(116);
        String token = buildToken("alice", "u1", permBits, 1);

        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, true, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstreamHeaders.get().getFirst("X-Authorities")).contains("PERM_people:employee:view");
    }

    /**
     * Strict validation rejects a token that does not have three dot-separated
     * parts.
     */
    @Test
    void strictValidation_insufficientTokenParts_returns401() {
        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, true, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer notajwttoken")
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Strict validation rejects a token whose header part is not valid Base64/JSON.
     */
    @Test
    void strictValidation_malformedJwtHeader_returns401() {
        GlobalFilter filter = new SecurityGatewayConfig(
                TEST_SECRET, true, Set.of("HS256"), new GatewayAuthProperties(), new SimpleMeterRegistry())
                .authFilter();

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/people/v1/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer !!!invalid.payload.sig")
                        .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PERM-011 — GatewayPermissionCatalog unit tests ───────────────────────

    /**
     * Unit tests for {@link GatewayPermissionCatalog} mapping and bounds handling.
     */
    @Nested
    class GatewayPermissionCatalogTests {

        /** Catalog version stays aligned with the token perm_ver claim. */
        @Test
        void catalogVersion_is1() {
            assertThat(GatewayPermissionCatalog.CATALOG_VERSION).isEqualTo(1);
        }

        /** Bit 0 maps to PERM_accounting:je:view. */
        @Test
        void authorityForBit_0_isAccountingJeView() {
            assertThat(GatewayPermissionCatalog.authorityForBit(0))
                    .isEqualTo("PERM_accounting:je:view");
        }

        /** Bit 214 maps to PERM_workorder:wip:view_all_locations. */
        @Test
        void authorityForBit_214_isWorkorderWipViewAllLocations() {
            assertThat(GatewayPermissionCatalog.authorityForBit(214))
                    .isEqualTo("PERM_workorder:wip:view_all_locations");
        }

        /** Negative bit indexes are out of range and return null. */
        @Test
        void authorityForBit_negativeIndex_returnsNull() {
            assertThat(GatewayPermissionCatalog.authorityForBit(-1)).isNull();
        }

        /** Index 215 is out of range and returns null. */
        @Test
        void authorityForBit_outOfRange_returnsNull() {
            assertThat(GatewayPermissionCatalog.authorityForBit(215)).isNull();
        }
    }

    // ── AUTH-007 — Gateway canonical claim enforcement alignment ─────────────

    /**
     * Verifies that pos-api-gateway JWT enforcement remains aligned with the
     * canonical AUTH-005 token claims contract ({@code perm_bits}, {@code perm_ver},
     * {@code sub}, {@code uid}, {@code jti}, {@code personId}).
     *
     * <p>These tests document and pin the alignment between pos-security-service
     * token issuance and pos-api-gateway token enforcement.
     *
     * <p>Issue: AUTH-007
     */
    @Nested
    class Auth007GatewayAlignmentTests {

        /**
         * A canonical AUTH-005 token (jti, sub, uid, username, perm_bits, perm_ver=1,
         * personId) is accepted by the gateway and the downstream context headers
         * are populated from the token claims.
         */
        @Test
        void canonicalToken_withFullAuth005Claims_isForwarded_withCorrectDownstreamHeaders() {
            String uid = UUID.randomUUID().toString();
            String personId = UUID.randomUUID().toString();
            // bits 116 = people:employee:view, 117 = people:employee:create
            String permBits = encodePermBits(116, 117);
            String token = buildCanonicalToken("alice", uid, personId, permBits, 1);

            GatewayAuthProperties props = new GatewayAuthProperties();
            props.setStripInboundIdentityHeaders(true);
            GlobalFilter filter = new SecurityGatewayConfig(
                    TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                    .authFilter();
            AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
            GatewayFilterChain chain = exchange -> {
                downstreamHeaders.set(exchange.getRequest().getHeaders());
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/people/v1/employees")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(downstreamHeaders.get()).isNotNull();
            assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("alice");
            assertThat(downstreamHeaders.get().getFirst("X-User-Id")).isEqualTo(uid);
            assertThat(downstreamHeaders.get().getFirst("X-Authorities"))
                    .contains("PERM_people:employee:view")
                    .contains("PERM_people:employee:create");
        }

        /**
         * A canonical token without the optional {@code personId} claim is accepted.
         * The {@code personId} is optional per the AUTH-005 contract.
         */
        @Test
        void canonicalToken_withoutPersonId_isAccepted() {
            String uid = UUID.randomUUID().toString();
            String permBits = encodePermBits(116);
            String token = buildCanonicalToken("alice", uid, null, permBits, 1);

            GlobalFilter filter = new SecurityGatewayConfig(
                    TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(),
                    new SimpleMeterRegistry())
                    .authFilter();
            AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
            GatewayFilterChain chain = exchange -> {
                downstreamHeaders.set(exchange.getRequest().getHeaders());
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/people/v1/employees")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(downstreamHeaders.get()).isNotNull();
            assertThat(downstreamHeaders.get().getFirst("X-User")).isEqualTo("alice");
            assertThat(downstreamHeaders.get().getFirst("X-User-Id")).isEqualTo(uid);
        }

        /**
         * A canonical token that does NOT include the legacy {@code authorities} claim
         * is resolved via the {@code perm_bits} decode path, not the legacy fallback.
         *
         * <p>Ensures the gateway never relies on an {@code authorities} claim when a
         * canonical token is presented.
         */
        @Test
        void canonicalToken_withNoAuthoritiesClaim_usesPermBitsPath_notLegacyFallback() {
            String uid = UUID.randomUUID().toString();
            // bit 0 = accounting:je:view
            String permBits = encodePermBits(0);
            // buildCanonicalToken never adds an 'authorities' claim
            String token = buildCanonicalToken("alice", uid, null, permBits, 1);

            GlobalFilter filter = new SecurityGatewayConfig(
                    TEST_SECRET, false, Set.of("HS256"), new GatewayAuthProperties(),
                    new SimpleMeterRegistry())
                    .authFilter();
            AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
            GatewayFilterChain chain = exchange -> {
                downstreamHeaders.set(exchange.getRequest().getHeaders());
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/accounting/v1/journal-entries")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(downstreamHeaders.get()).isNotNull();
            String authorities = downstreamHeaders.get().getFirst("X-Authorities");
            assertThat(authorities)
                    .contains("PERM_accounting:je:view")
                    .doesNotContain("ROLE_");
        }

        /**
         * A spoofed {@code X-Authorities} header in the inbound request is stripped
         * and replaced with the token-derived perm_bits value.
         *
         * <p>Verifies the gateway trust boundary is enforced: callers cannot inject
         * authority claims via headers when presenting a canonical token.
         */
        @Test
        void canonicalToken_spoofedXAuthoritiesHeader_isStrippedAndReplacedByPermBitsValue() {
            String uid = UUID.randomUUID().toString();
            // bit 116 = people:employee:view only
            String permBits = encodePermBits(116);
            String token = buildCanonicalToken("alice", uid, null, permBits, 1);

            GatewayAuthProperties props = new GatewayAuthProperties();
            props.setStripInboundIdentityHeaders(true);
            GlobalFilter filter = new SecurityGatewayConfig(
                    TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                    .authFilter();
            AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
            GatewayFilterChain chain = exchange -> {
                downstreamHeaders.set(exchange.getRequest().getHeaders());
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/people/v1/employees")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("X-Authorities", "ROLE_SUPER_ADMIN,PERM_all:access:grant")
                            .build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(downstreamHeaders.get()).isNotNull();
            String downstreamAuthorities = downstreamHeaders.get().getFirst("X-Authorities");
            assertThat(downstreamAuthorities)
                    .doesNotContain("ROLE_SUPER_ADMIN")
                    .doesNotContain("PERM_all:access:grant")
                    .contains("PERM_people:employee:view");
        }

        /**
         * A spoofed {@code X-User} header in the inbound request is stripped and
         * replaced with the token {@code sub} claim value.
         *
         * <p>Verifies that callers cannot override the authenticated identity via
         * the X-User header when a canonical token is presented.
         */
        @Test
        void canonicalToken_inboundXUserHeader_isStrippedAndReplacedByTokenSubject() {
            String uid = UUID.randomUUID().toString();
            String permBits = encodePermBits(116);
            String token = buildCanonicalToken("alice", uid, null, permBits, 1);

            GatewayAuthProperties props = new GatewayAuthProperties();
            props.setStripInboundIdentityHeaders(true);
            GlobalFilter filter = new SecurityGatewayConfig(
                    TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                    .authFilter();
            AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
            GatewayFilterChain chain = exchange -> {
                downstreamHeaders.set(exchange.getRequest().getHeaders());
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/people/v1/employees")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("X-User", "mallory-injected-identity")
                            .build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(downstreamHeaders.get()).isNotNull();
            assertThat(downstreamHeaders.get().getFirst("X-User"))
                    .isEqualTo("alice")
                    .isNotEqualTo("mallory-injected-identity");
        }

        /**
         * Spoofed inbound {@code X-User-Id} header is stripped and replaced with
         * the {@code uid} claim value from the token.
         *
         * <p>Verifies that callers cannot override the authenticated user identity via
         * the X-User-Id header when a canonical token is presented.
         */
        @Test
        void canonicalToken_spoofedXUserIdHeader_isStrippedAndReplacedByTokenUid() {
            String uid = UUID.randomUUID().toString();
            String permBits = encodePermBits(116);
            String token = buildCanonicalToken("alice", uid, null, permBits, 1);

            GatewayAuthProperties props = new GatewayAuthProperties();
            props.setStripInboundIdentityHeaders(true);
            GlobalFilter filter = new SecurityGatewayConfig(
                    TEST_SECRET, false, Set.of("HS256"), props, new SimpleMeterRegistry())
                    .authFilter();
            AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
            GatewayFilterChain chain = exchange -> {
                downstreamHeaders.set(exchange.getRequest().getHeaders());
                return Mono.empty();
            };

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/people/v1/employees")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("X-User-Id", "spoofed-user-id-injected-by-caller")
                            .build());

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(downstreamHeaders.get()).isNotNull();
            assertThat(downstreamHeaders.get().getFirst("X-User-Id"))
                    .isEqualTo(uid)
                    .isNotEqualTo("spoofed-user-id-injected-by-caller");
        }
    }
}
