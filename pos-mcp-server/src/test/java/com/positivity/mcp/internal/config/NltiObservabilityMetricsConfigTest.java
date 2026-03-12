package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link NltiObservabilityMetricsConfig} registers all 5 metric
 * beans
 * correctly against a MeterRegistry (NLTI-009 observability metrics).
 *
 * <p>
 * Tests use a real {@link SimpleMeterRegistry} — no Spring context required.
 * Each bean factory method is called directly to ensure JaCoCo records
 * instruction
 * coverage for the config class.
 *
 * Issue: NLTI-009
 */
class NltiObservabilityMetricsConfigTest {

    private NltiObservabilityMetricsConfig config;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        config = new NltiObservabilityMetricsConfig();
        registry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("nltRequestCount bean registers nlt.request.count counter")
    void nltRequestCount_registersCounter() {
        Counter counter = config.nltRequestCount(registry);

        assertThat(counter).isNotNull();
        assertThat(registry.counter("nlt.request.count")).isSameAs(counter);
    }

    @Test
    @DisplayName("nltErrorCount bean registers nlt.error.count counter")
    void nltErrorCount_registersCounter() {
        Counter counter = config.nltErrorCount(registry);

        assertThat(counter).isNotNull();
        assertThat(registry.counter("nlt.error.count")).isSameAs(counter);
    }

    @Test
    @DisplayName("nltRequestLatencyMs bean registers nlt.request.latency_ms timer")
    void nltRequestLatencyMs_registersTimer() {
        Timer timer = config.nltRequestLatencyMs(registry);

        assertThat(timer).isNotNull();
        assertThat(registry.timer("nlt.request.latency_ms")).isSameAs(timer);
    }

    @Test
    @DisplayName("nltPlanningLatencyMs bean registers nlt.planning.latency_ms timer")
    void nltPlanningLatencyMs_registersTimer() {
        Timer timer = config.nltPlanningLatencyMs(registry);

        assertThat(timer).isNotNull();
        assertThat(registry.timer("nlt.planning.latency_ms")).isSameAs(timer);
    }

    @Test
    @DisplayName("nltExecutionLatencyMs bean registers nlt.execution.latency_ms timer")
    void nltExecutionLatencyMs_registersTimer() {
        Timer timer = config.nltExecutionLatencyMs(registry);

        assertThat(timer).isNotNull();
        assertThat(registry.timer("nlt.execution.latency_ms")).isSameAs(timer);
    }
}
