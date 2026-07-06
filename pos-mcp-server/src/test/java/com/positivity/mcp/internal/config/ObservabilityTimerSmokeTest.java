package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the LatencyUtils runtime dependency. Registering any Micrometer {@link Timer} loads
 * {@code io.micrometer.core.instrument.AbstractTimer}, which references
 * {@code org.LatencyUtils.PauseDetector}. Without the LatencyUtils dependency this throws
 * {@code NoClassDefFoundError}, which previously broke {@code NltiObservabilityMetricsConfig}
 * bean creation and blocked the entire application context (and therefore any live boot).
 */
class ObservabilityTimerSmokeTest {

    @Test
    @DisplayName("Micrometer Timer registers without NoClassDefFoundError (LatencyUtils present)")
    void timerRegisters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = Timer.builder("nlt.request.latency").register(registry);
        timer.record(Duration.ofMillis(1));
        assertThat(timer.count()).isEqualTo(1L);
    }
}
