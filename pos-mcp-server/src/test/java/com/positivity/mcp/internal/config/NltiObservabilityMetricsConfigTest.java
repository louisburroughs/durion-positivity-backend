package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
    @DisplayName("agentCacheSizeGauge registers session cache size gauge")
    void agentCacheSizeGauge_registersGauge() {
        // Micrometer holds the gauged object WEAKLY; a temporary here is collectable before value()
        // is read, and the gauge then reads NaN (#1811: CI hit exactly that). Keep the reference alive
        // for the life of the test, and invite the collector so the test exercises the condition.
        SessionAgentCacheMetrics metrics = cacheMetricsWithSize(7L);
        Gauge gauge = config.agentCacheSizeGauge(registry, metrics);
        System.gc();

        assertThat(gauge).isNotNull();
        assertThat(registry.get("mcp.agent.cache.size").gauge()).isSameAs(gauge);
        assertThat(gauge.value()).isEqualTo(7D);
    }

    @Test
    @DisplayName("streamingAgentCacheSizeGauge registers streaming cache size gauge")
    void streamingAgentCacheSizeGauge_registersGauge() {
        StreamingSessionAgentCacheMetrics metrics = streamingCacheMetricsWithSize(3L);
        Gauge gauge = config.streamingAgentCacheSizeGauge(registry, metrics);
        System.gc();

        assertThat(gauge).isNotNull();
        assertThat(registry.get("mcp.streaming.agent.cache.size").gauge()).isSameAs(gauge);
        assertThat(gauge.value()).isEqualTo(3D);
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
    @DisplayName("nltRequestLatencyMs bean registers nlt.request.latency timer")
    void nltRequestLatencyMs_registersTimer() {
        Timer timer = config.nltRequestLatencyMs(registry);

        assertThat(timer).isNotNull();
        assertThat(registry.timer("nlt.request.latency")).isSameAs(timer);
    }

    @Test
    @DisplayName("nltPlanningLatencyMs bean registers nlt.planning.latency timer")
    void nltPlanningLatencyMs_registersTimer() {
        Timer timer = config.nltPlanningLatencyMs(registry);

        assertThat(timer).isNotNull();
        assertThat(registry.timer("nlt.planning.latency")).isSameAs(timer);
    }

    @Test
    @DisplayName("nltExecutionLatencyMs bean registers nlt.execution.latency timer")
    void nltExecutionLatencyMs_registersTimer() {
        Timer timer = config.nltExecutionLatencyMs(registry);

        assertThat(timer).isNotNull();
        assertThat(registry.timer("nlt.execution.latency")).isSameAs(timer);
    }

    private SessionAgentCacheMetrics cacheMetricsWithSize(long cacheSize) {
        return () -> cacheSize;
    }

    private StreamingSessionAgentCacheMetrics streamingCacheMetricsWithSize(long cacheSize) {
        return () -> cacheSize;
    }
}
