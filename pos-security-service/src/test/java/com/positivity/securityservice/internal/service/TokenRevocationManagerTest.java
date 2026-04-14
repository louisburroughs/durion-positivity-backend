package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.retry.Retry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class TokenRevocationManagerTest {

    @Mock
    private RedisTemplate<String, Boolean> redisTemplate;

    @Mock
    private ValueOperations<String, Boolean> valueOps;

    @SuppressWarnings("unchecked")
    private ObjectProvider<RedisTemplate<String, Boolean>> redisProvider() {
        ObjectProvider<RedisTemplate<String, Boolean>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RedisTemplate<String, Boolean>> nullRedisProvider() {
        ObjectProvider<RedisTemplate<String, Boolean>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<io.github.resilience4j.retry.Retry> nullRetryProvider() {
        ObjectProvider<io.github.resilience4j.retry.Retry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private TokenRevocationManager managerWithRedis() {
        return new TokenRevocationManager(redisProvider(), nullRetryProvider(), true);
    }

    private TokenRevocationManager managerRedisDisabled() {
        return new TokenRevocationManager(nullRedisProvider(), nullRetryProvider(), false);
    }

    @BeforeEach
    void setUpRedisTemplate() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ---- revokeToken ----

    @Test
    @DisplayName("revokeToken: blank JTI throws IllegalArgumentException")
    void revokeToken_blankJti_throws() {
        TokenRevocationManager mgr = managerWithRedis();

        assertThatThrownBy(() -> mgr.revokeToken("", 3600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");

        assertThatThrownBy(() -> mgr.revokeToken(null, 3600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");
    }

    @Test
    @DisplayName("revokeToken: non-positive expiration throws IllegalArgumentException")
    void revokeToken_nonPositiveExpiration_throws() {
        TokenRevocationManager mgr = managerWithRedis();

        assertThatThrownBy(() -> mgr.revokeToken("some-jti", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expiration seconds must be positive");

        assertThatThrownBy(() -> mgr.revokeToken("some-jti", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expiration seconds must be positive");
    }

    @Test
    @DisplayName("revokeToken: redis disabled returns false without touching redis")
    void revokeToken_redisDisabled_returnsFalse() {
        TokenRevocationManager mgr = managerRedisDisabled();

        boolean result = mgr.revokeToken("some-jti", 3600);

        assertThat(result).isFalse();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("revokeToken: redis available, no retry decorator, success")
    void revokeToken_redisAvailableNoRetry_success() {
        TokenRevocationManager mgr = managerWithRedis();

        boolean result = mgr.revokeToken("jti-abc", 3600);

        assertThat(result).isTrue();
        verify(valueOps).set(eq("jwt:revoked:jti-abc"), eq(true), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("revokeToken: redis write throws, returns false (graceful degradation)")
    void revokeToken_redisThrows_returnsFalse() {
        TokenRevocationManager mgr = managerWithRedis();
        doThrow(new RuntimeException("Connection refused"))
                .when(valueOps)
                .set(any(), any(), anyLong(), any(TimeUnit.class));

        boolean result = mgr.revokeToken("jti-abc", 3600);

        assertThat(result).isFalse();
    }

    // ---- isRevoked ----

    @Test
    @DisplayName("isRevoked: blank JTI throws IllegalArgumentException")
    void isRevoked_blankJti_throws() {
        TokenRevocationManager mgr = managerWithRedis();

        assertThatThrownBy(() -> mgr.isRevoked(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");

        assertThatThrownBy(() -> mgr.isRevoked(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");
    }

    @Test
    @DisplayName("isRevoked: redis disabled returns false")
    void isRevoked_redisDisabled_returnsFalse() {
        TokenRevocationManager mgr = managerRedisDisabled();

        assertThat(mgr.isRevoked("some-jti")).isFalse();
    }

    @Test
    @DisplayName("isRevoked: token is in revocation list returns true")
    void isRevoked_tokenFound_returnsTrue() {
        TokenRevocationManager mgr = managerWithRedis();
        when(valueOps.get("jwt:revoked:jti-xyz")).thenReturn(true);

        assertThat(mgr.isRevoked("jti-xyz")).isTrue();
    }

    @Test
    @DisplayName("isRevoked: token not in revocation list returns false")
    void isRevoked_tokenNotFound_returnsFalse() {
        TokenRevocationManager mgr = managerWithRedis();
        when(valueOps.get("jwt:revoked:jti-xyz")).thenReturn(null);

        assertThat(mgr.isRevoked("jti-xyz")).isFalse();
    }

    @Test
    @DisplayName("isRevoked: redis throws returns false (graceful degradation)")
    void isRevoked_redisThrows_returnsFalse() {
        TokenRevocationManager mgr = managerWithRedis();
        when(valueOps.get(any())).thenThrow(new RuntimeException("Redis unavailable"));

        assertThat(mgr.isRevoked("jti-xyz")).isFalse();
    }

    // ---- unrevokeToken ----

    @Test
    @DisplayName("unrevokeToken: blank JTI throws IllegalArgumentException")
    void unrevokeToken_blankJti_throws() {
        TokenRevocationManager mgr = managerWithRedis();

        assertThatThrownBy(() -> mgr.unrevokeToken(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");

        assertThatThrownBy(() -> mgr.unrevokeToken(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");
    }

    @Test
    @DisplayName("unrevokeToken: redis disabled returns false")
    void unrevokeToken_redisDisabled_returnsFalse() {
        TokenRevocationManager mgr = managerRedisDisabled();

        assertThat(mgr.unrevokeToken("some-jti")).isFalse();
    }

    @Test
    @DisplayName("unrevokeToken: key deleted successfully returns true")
    void unrevokeToken_keyDeleted_returnsTrue() {
        TokenRevocationManager mgr = managerWithRedis();
        when(redisTemplate.delete("jwt:revoked:jti-abc")).thenReturn(true);

        assertThat(mgr.unrevokeToken("jti-abc")).isTrue();
    }

    @Test
    @DisplayName("unrevokeToken: key did not exist returns false")
    void unrevokeToken_keyMissing_returnsFalse() {
        TokenRevocationManager mgr = managerWithRedis();
        when(redisTemplate.delete("jwt:revoked:jti-abc")).thenReturn(false);

        assertThat(mgr.unrevokeToken("jti-abc")).isFalse();
    }

    @Test
    @DisplayName("unrevokeToken: redis throws returns false (graceful degradation)")
    void unrevokeToken_redisThrows_returnsFalse() {
        TokenRevocationManager mgr = managerWithRedis();
        when(redisTemplate.delete(any(String.class))).thenThrow(new RuntimeException("Redis error"));

        assertThat(mgr.unrevokeToken("jti-abc")).isFalse();
    }

    // ---- clearAllRevoked ----

    @Test
    @DisplayName("clearAllRevoked: redis disabled returns 0")
    void clearAllRevoked_redisDisabled_returnsZero() {
        TokenRevocationManager mgr = managerRedisDisabled();

        assertThat(mgr.clearAllRevoked()).isEqualTo(0L);
    }

    @Test
    @DisplayName("clearAllRevoked: redis throws returns 0 (graceful degradation)")
    void clearAllRevoked_redisThrows_returnsZero() {
        TokenRevocationManager mgr = managerWithRedis();
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis error"));

        assertThat(mgr.clearAllRevoked()).isEqualTo(0L);
    }

    // ---- retry decorator path ----

    @SuppressWarnings("unchecked")
    private ObjectProvider<Retry> retryProvider(Retry retry) {
        ObjectProvider<Retry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(retry);
        return provider;
    }

    @Test
    @DisplayName("revokeToken: retry decorator path succeeds when Redis write succeeds")
    void revokeToken_withRetryDecorator_success() {
        Retry retry = Retry.ofDefaults("test");
        TokenRevocationManager mgr = new TokenRevocationManager(redisProvider(), retryProvider(retry), true);

        boolean result = mgr.revokeToken("jti-retry", 3600);

        assertThat(result).isTrue();
        verify(valueOps).set(eq("jwt:revoked:jti-retry"), eq(true), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("revokeToken: retry decorator path returns false when all attempts fail")
    void revokeToken_withRetryDecorator_allAttemptsFail_returnsFalse() {
        Retry retry = io.github.resilience4j.retry.Retry.of(
                "fast-fail",
                io.github.resilience4j.retry.RetryConfig.custom().maxAttempts(1).build());
        TokenRevocationManager mgr = new TokenRevocationManager(redisProvider(), retryProvider(retry), true);
        doThrow(new RuntimeException("Redis down")).when(valueOps).set(any(), any(), anyLong(), any(TimeUnit.class));

        boolean result = mgr.revokeToken("jti-retry-fail", 3600);

        assertThat(result).isFalse();
    }

    // ---- clearAllRevoked with cursor ----

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("clearAllRevoked: empty scan returns 0")
    void clearAllRevoked_emptyScan_returnsZero() {
        RedisOperations<String, Boolean> ops = mock(RedisOperations.class);
        Cursor<String> emptyCursor = mock(Cursor.class);
        when(emptyCursor.hasNext()).thenReturn(false);
        when(ops.scan(any(ScanOptions.class))).thenReturn(emptyCursor);
        when(valueOps.getOperations()).thenReturn(ops);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TokenRevocationManager mgr = new TokenRevocationManager(redisProvider(), nullRetryProvider(), true);

        assertThat(mgr.clearAllRevoked()).isEqualTo(0L);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("clearAllRevoked: scan returns keys, deletes them and returns count")
    void clearAllRevoked_withKeys_returnsCount() {
        RedisOperations<String, Boolean> ops = mock(RedisOperations.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("jwt:revoked:a", "jwt:revoked:b");
        when(ops.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.getOperations()).thenReturn(ops);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.delete(any(List.class))).thenReturn(2L);

        TokenRevocationManager mgr = new TokenRevocationManager(redisProvider(), nullRetryProvider(), true);

        assertThat(mgr.clearAllRevoked()).isEqualTo(2L);
    }
}
