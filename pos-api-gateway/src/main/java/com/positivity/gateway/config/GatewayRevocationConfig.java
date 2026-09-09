package com.positivity.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Wires the gateway's token-revocation check (#1883).
 *
 * <p>The check is resolved once at startup rather than per request, and it degrades in two
 * distinct ways that are deliberately kept apart in the logs: switched off by configuration
 * ({@code pos.gateway.security.revocation-check.enabled=false}), or switched on with no reactive
 * Redis template on the context. Both leave revoked tokens passing until {@code exp}, so both say
 * so at WARN — an operator reading startup logs should never have to infer it.
 */
@Configuration
@EnableConfigurationProperties(GatewayRevocationProperties.class)
public class GatewayRevocationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayRevocationConfig.class);

    @Bean
    public TokenRevocationChecker tokenRevocationChecker(
            @NonNull GatewayRevocationProperties properties,
            @NonNull ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider,
            @NonNull MeterRegistry meterRegistry) {
        if (!properties.isEnabled()) {
            LOG.warn("Gateway token-revocation check is DISABLED by configuration"
                    + " (pos.gateway.security.revocation-check.enabled=false);"
                    + " a revoked token will pass the gateway until its exp");
            return TokenRevocationChecker.DISABLED;
        }

        ReactiveStringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            LOG.warn("Gateway token-revocation check is enabled but no ReactiveStringRedisTemplate is available;"
                    + " a revoked token will pass the gateway until its exp");
            return TokenRevocationChecker.DISABLED;
        }

        LOG.info(
                "Gateway token-revocation check enabled; lookup timeout={}ms, fail-open on Redis unavailability",
                properties.getTimeout().toMillis());
        return new RedisTokenRevocationChecker(redisTemplate, meterRegistry, properties.getTimeout());
    }
}
