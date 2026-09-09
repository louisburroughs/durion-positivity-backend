package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Behaviour of the gateway's revocation lookup (#1883): what it asks Redis, and what it does when
 * Redis will not answer.
 */
class RedisTokenRevocationCheckerTest {

    private static final String JTI = "019507b4-1f3a-7000-8e04-5c9d3a4f6e12";
    private static final String KEY = "jwt:revoked:" + JTI;
    private static final Duration TIMEOUT = Duration.ofMillis(150);

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedisTokenRevocationChecker checker =
            new RedisTokenRevocationChecker(redisTemplate, registry, TIMEOUT);

    @Test
    @DisplayName("asks for the key TokenRevocationManager writes, not a variation of it")
    void looksUpTheSharedKey() {
        when(redisTemplate.hasKey(KEY)).thenReturn(Mono.just(true));

        assertThat(checker.isRevoked(JTI).block()).isTrue();
        verify(redisTemplate).hasKey(KEY);
    }

    @Test
    @DisplayName("a missing key is not a revocation")
    void absentKeyIsNotRevoked() {
        when(redisTemplate.hasKey(KEY)).thenReturn(Mono.just(false));

        assertThat(checker.isRevoked(JTI).block()).isFalse();
        assertThat(registry.timer("auth.token.revocation.check.duration", "outcome", "clear")
                        .count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Redis unavailable → fail open, and say so on the degraded counter")
    void redisFailureFailsOpen() {
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(new RedisConnectionFailureException("no connection")));

        assertThat(checker.isRevoked(JTI).block()).isFalse();
        assertThat(registry.counter("auth.token.revocation.degraded", "reason", "error")
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.timer("auth.token.revocation.check.duration", "outcome", "degraded")
                        .count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a lookup slower than the timeout fails open rather than holding the request")
    void slowLookupTimesOutAndFailsOpen() {
        RedisTokenRevocationChecker impatient =
                new RedisTokenRevocationChecker(redisTemplate, registry, Duration.ofMillis(20));
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(true).delayElement(Duration.ofSeconds(5)));

        assertThat(impatient.isRevoked(JTI).block()).isFalse();
        assertThat(registry.counter("auth.token.revocation.degraded", "reason", "timeout")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a revocation is still reported after the store recovers")
    void recoversAfterFailure() {
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(new RedisConnectionFailureException("no connection")))
                .thenReturn(Mono.just(true));

        assertThat(checker.isRevoked(JTI).block()).isFalse();
        assertThat(checker.isRevoked(JTI).block()).isTrue();
        assertThat(registry.counter("auth.token.revocation.degraded", "reason", "error")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the disabled checker answers without touching Redis")
    void disabledCheckerDoesNotTouchRedis() {
        assertThat(TokenRevocationChecker.DISABLED.isRevoked(JTI).block()).isFalse();
        verifyNoInteractions(redisTemplate);
    }
}
