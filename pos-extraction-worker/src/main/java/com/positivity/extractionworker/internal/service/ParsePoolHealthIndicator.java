package com.positivity.extractionworker.internal.service;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports pool saturation as details. Always UP: a full pool means callers are told to retry, not that
 * the worker is unserviceable, and reporting DOWN would restart a container that is doing its job (the
 * Compose healthcheck reads the aggregate status).
 */
@Component("parsePool")
public class ParsePoolHealthIndicator implements HealthIndicator {

    private final ParsePool pool;

    public ParsePoolHealthIndicator(@NonNull ParsePool pool) {
        this.pool = pool;
    }

    @Override
    public Health health() {
        int inUse = pool.inUse();
        return Health.up()
                .withDetail("inUse", inUse)
                .withDetail("capacity", pool.capacity())
                .withDetail("saturated", inUse >= pool.capacity())
                .build();
    }
}
