package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.BitSet;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * The cross-process half of #1883, against a real Redis: a token revoked the way
 * pos-security-service revokes one is rejected by the gateway on the next request.
 *
 * <p>The unit tests stub the lookup, so they cannot see the failure mode that actually mattered
 * here — the two processes disagreeing about how a key is encoded. {@code RedisTemplate}'s default
 * key serializer is {@code JdkSerializationRedisSerializer}, and under it the gateway's
 * {@code EXISTS jwt:revoked:{jti}} silently misses every revocation ever written. This test writes
 * through a template configured exactly as
 * {@code pos-security-service}'s {@code RedisConfig.jwtRevocationRedisTemplate} and reads through
 * the gateway's own filter, so that disagreement fails the build.
 *
 * <p>The write-side configuration is deliberately duplicated rather than imported: the modules
 * have no dependency on each other by design, and a copy that has to be kept in step is the point
 * — if pos-security-service changes its serializers, this test is where the platform finds out.
 */
class GatewayTokenRevocationIT {

    private static final String TEST_SECRET = "test-jwt-secret-key-01234567890123456789";
    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    private static final String REVOCATION_KEY_PREFIX = "jwt:revoked:";

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;

    @BeforeAll
    static void startRedis() {
        String externalRedis = System.getenv("REDIS_TEST_URI");
        String host;
        int port;
        if (externalRedis != null && !externalRedis.isBlank()) {
            String[] parts = externalRedis.split(":");
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        } else {
            redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            redis.start();
            host = redis.getHost();
            port = redis.getMappedPort(6379);
        }
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    /** Mirrors {@code RedisConfig.jwtRevocationRedisTemplate} in pos-security-service. */
    private static RedisTemplate<String, Boolean> writeSideTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Boolean> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /** Mirrors {@code TokenRevocationManager.revokeToken}. */
    private static void revoke(String jti, long ttlSeconds) {
        writeSideTemplate(connectionFactory)
                .opsForValue()
                .set(REVOCATION_KEY_PREFIX + jti, true, ttlSeconds, TimeUnit.SECONDS);
    }

    private static String buildToken(String jti) {
        BitSet bits = new BitSet();
        bits.set(116);
        return Jwts.builder()
                .id(jti)
                .subject("alice")
                .issuer("pos-security-service")
                .audience()
                .add("api-gateway")
                .and()
                .claim("uid", "u1")
                .claim("perm_bits", Base64.getUrlEncoder().withoutPadding().encodeToString(bits.toByteArray()))
                .claim("perm_ver", GatewayPermissionCatalog.CATALOG_VERSION)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    private static GlobalFilter gatewayFilter() {
        TokenRevocationChecker checker = new RedisTokenRevocationChecker(
                new ReactiveStringRedisTemplate(connectionFactory), new SimpleMeterRegistry(), Duration.ofSeconds(2));
        return new SecurityGatewayConfig(
                        TEST_SECRET,
                        false,
                        Set.of("HS256"),
                        new GatewayAuthProperties(),
                        new SimpleMeterRegistry(),
                        checker)
                .authFilter();
    }

    private static MockServerWebExchange request(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/people/v1/employees")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());
    }

    @Test
    @DisplayName("a token revoked the way pos-security-service revokes one is rejected on the next request")
    void revokedTokenIsRejected() {
        String jti = UUID.randomUUID().toString();
        revoke(jti, 3600);
        AtomicBoolean forwarded = new AtomicBoolean(false);

        var exchange = request(buildToken(jti));
        gatewayFilter()
                .filter(exchange, ignored -> {
                    forwarded.set(true);
                    return Mono.empty();
                })
                .block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("TOKEN_REVOKED");
    }

    @Test
    @DisplayName("a token that was never revoked is forwarded")
    void unrevokedTokenIsForwarded() {
        AtomicBoolean forwarded = new AtomicBoolean(false);

        var exchange = request(buildToken(UUID.randomUUID().toString()));
        gatewayFilter()
                .filter(exchange, ignored -> {
                    forwarded.set(true);
                    return Mono.empty();
                })
                .block();

        assertThat(forwarded.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("revocation keys are plain strings, so clearAllRevoked's jwt:revoked:* SCAN can match them")
    void revocationKeysAreScannable() {
        String jti = UUID.randomUUID().toString();
        revoke(jti, 3600);

        StringRedisTemplate scanner = new StringRedisTemplate(connectionFactory);

        assertThat(scanner.keys(REVOCATION_KEY_PREFIX + "*")).contains(REVOCATION_KEY_PREFIX + jti);
    }

    @Test
    @DisplayName("the key carries the token's TTL, so a revocation expires with the token it revokes")
    void revocationKeyExpiresWithTheToken() {
        String jti = UUID.randomUUID().toString();
        revoke(jti, 120);

        Long ttl = new StringRedisTemplate(connectionFactory).getExpire(REVOCATION_KEY_PREFIX + jti);

        assertThat(ttl).isBetween(1L, 120L);
    }
}
