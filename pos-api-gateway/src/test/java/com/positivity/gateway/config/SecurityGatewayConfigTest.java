package com.positivity.gateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Date;
import java.util.Set;
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
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for gateway JWT validation, permission decoding, and
 * header trust-boundary behavior.
 *
 * Issue: PERM-006, PERM-007, PERM-008, PERM-009, PERM-010, PERM-011.
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

    // ── PERM-006 / PERM-007 — local JWT validation & bitset decode ───────────

        /** Valid token with people permission bits is forwarded with decoded authorities. */
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

    /** Missing perm_bits is rejected when token identity is required and perm_ver is valid. */
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

        /** Spoofed inbound authorities are stripped and replaced with token-derived authorities. */
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

        /** Legacy token authorities claim is accepted when perm_ver and perm_bits are absent. */
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

        /** Empty perm_bits is accepted and yields an empty/absent authorities header. */
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

    // ── PERM-011 — GatewayPermissionCatalog unit tests ───────────────────────

        /** Unit tests for {@link GatewayPermissionCatalog} mapping and bounds handling. */
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
}
