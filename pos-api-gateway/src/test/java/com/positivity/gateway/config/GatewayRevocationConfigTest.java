package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Which checker the gateway ends up with (#1883). Both degraded wirings resolve to
 * {@link TokenRevocationChecker#DISABLED}, so the distinction that matters is that neither one
 * fails startup: an unreachable revocation store must not take the platform's ingress down.
 */
class GatewayRevocationConfigTest {

    private final GatewayRevocationConfig config = new GatewayRevocationConfig();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> provider(ReactiveStringRedisTemplate template) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    @Test
    @DisplayName("enabled with a template → the Redis-backed check")
    void enabledWithTemplate() {
        GatewayRevocationProperties properties = new GatewayRevocationProperties();

        TokenRevocationChecker checker =
                config.tokenRevocationChecker(properties, provider(mock(ReactiveStringRedisTemplate.class)), registry);

        assertThat(checker).isInstanceOf(RedisTokenRevocationChecker.class);
    }

    @Test
    @DisplayName("switched off by configuration → no check, and startup still succeeds")
    void disabledByConfiguration() {
        GatewayRevocationProperties properties = new GatewayRevocationProperties();
        properties.setEnabled(false);

        TokenRevocationChecker checker =
                config.tokenRevocationChecker(properties, provider(mock(ReactiveStringRedisTemplate.class)), registry);

        assertThat(checker).isSameAs(TokenRevocationChecker.DISABLED);
    }

    @Test
    @DisplayName("enabled but no template on the context → no check, and startup still succeeds")
    void enabledWithoutTemplate() {
        GatewayRevocationProperties properties = new GatewayRevocationProperties();

        TokenRevocationChecker checker = config.tokenRevocationChecker(properties, provider(null), registry);

        assertThat(checker).isSameAs(TokenRevocationChecker.DISABLED);
    }

    @Test
    @DisplayName("the default is on, with a bounded lookup")
    void defaultsAreSecure() {
        GatewayRevocationProperties properties = new GatewayRevocationProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofMillis(150));
    }
}
