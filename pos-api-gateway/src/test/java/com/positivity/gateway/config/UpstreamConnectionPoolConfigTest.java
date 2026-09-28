package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the upstream connection-pool eviction settings (#2306).
 *
 * <p>Reactor Netty's pool never evicts idle connections unless told to, while every routed service
 * (embedded Tomcat) closes an idle keep-alive connection after its keep-alive timeout — Tomcat's 60s
 * default, since no service sets one. Without eviction the gateway occasionally reused a connection
 * the upstream had just closed and answered a non-idempotent POST with 500 for a request the
 * service never saw. The values are bound from the real application.yml into the gateway's own
 * {@link HttpClientProperties}, so a renamed or misplaced key fails here rather than silently
 * falling back to "never evict".
 */
class UpstreamConnectionPoolConfigTest {

    /** Tomcat's keep-alive timeout when neither keep-alive-timeout nor connection-timeout is set. */
    private static final Duration UPSTREAM_TOMCAT_KEEP_ALIVE = Duration.ofSeconds(60);

    private static HttpClientProperties.Pool pool;

    @BeforeAll
    static void bindPool() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        pool = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.cloud.gateway.server.webflux.httpclient", HttpClientProperties.class)
                .map(HttpClientProperties::getPool)
                .orElseThrow(
                        () -> new AssertionError("spring.cloud.gateway.server.webflux.httpclient is not configured"));
    }

    @Test
    @DisplayName("idle upstream connections are retired well before the upstream Tomcat closes them")
    void maxIdleTimeIsBelowUpstreamKeepAlive() {
        assertThat(pool.getMaxIdleTime())
                .as("pool.max-idle-time must be set, otherwise idle connections are never evicted")
                .isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(UPSTREAM_TOMCAT_KEEP_ALIVE.dividedBy(2));
    }

    @Test
    @DisplayName("idle connections are evicted in the background, not only on acquire")
    void evictsInBackground() {
        assertThat(pool.getEvictionInterval())
                .as("pool.eviction-interval must be positive (Duration.ZERO disables background eviction)")
                .isPositive()
                .isLessThanOrEqualTo(pool.getMaxIdleTime());
    }
}
