package com.positivity.mcp.internal.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NltiObservabilityMetricsConfig {

    @Bean
    public Counter nltRequestCount(MeterRegistry registry) {
        return Counter.builder("nlt.request.count").register(registry);
    }

    @Bean
    public Counter nltErrorCount(MeterRegistry registry) {
        return Counter.builder("nlt.error.count").register(registry);
    }

    @Bean
    public Timer nltRequestLatencyMs(MeterRegistry registry) {
        return Timer.builder("nlt.request.latency_ms").register(registry);
    }

    @Bean
    public Timer nltPlanningLatencyMs(MeterRegistry registry) {
        return Timer.builder("nlt.planning.latency_ms").register(registry);
    }

    @Bean
    public Timer nltExecutionLatencyMs(MeterRegistry registry) {
        return Timer.builder("nlt.execution.latency_ms").register(registry);
    }
}