package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The worker's Micrometer meters (tags {@code tenant}, {@code operation}, {@code outcome}, {@code reason}):
 * {@code extraction.calls}, the timer {@code extraction.duration} and {@code extraction.limit_breaches}.
 * Tags carry only closed vocabularies and the tenant UUID; never a file name or value.
 */
@Component
public class ExtractionMetrics {

    static final String NONE = "none";

    private final MeterRegistry registry;

    public ExtractionMetrics(@NonNull MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(
            @NonNull String tenant,
            @NonNull String operation,
            @NonNull String outcome,
            @Nullable ReasonCode reason,
            @NonNull Duration elapsed) {
        String reasonTag = reason == null ? NONE : reason.name();
        Tags tags = Tags.of("tenant", tenant, "operation", operation, "outcome", outcome, "reason", reasonTag);
        registry.counter("extraction.calls", tags).increment();
        registry.timer("extraction.duration", tags).record(elapsed);
        if (isLimitBreach(reason)) {
            registry.counter("extraction.limit_breaches", tags).increment();
        }
    }

    private static boolean isLimitBreach(@Nullable ReasonCode reason) {
        return reason == ReasonCode.RESOURCE_LIMIT_EXCEEDED
                || reason == ReasonCode.PAGE_LIMIT_EXCEEDED
                || reason == ReasonCode.DECOMPRESSION_LIMIT_EXCEEDED;
    }
}
