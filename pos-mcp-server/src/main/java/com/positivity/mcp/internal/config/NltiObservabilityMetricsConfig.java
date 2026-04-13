package com.positivity.mcp.internal.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.positivity.mcp.service.SessionAgentCacheMetrics;
import com.positivity.mcp.service.StreamingSessionAgentCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class NltiObservabilityMetricsConfig {

    @Bean
    @Profile("alpha")
    public Gauge agentCacheSizeGauge(MeterRegistry registry,
            SessionAgentCacheMetrics sessionAgentMetrics) {
        return Gauge.builder("mcp.agent.cache.size",
                sessionAgentMetrics, SessionAgentCacheMetrics::getCacheSize)
                .description("Current number of cached LangChain4j agent sessions")
                .register(registry);
    }

    @Bean
    @Profile("alpha")
    public Gauge streamingAgentCacheSizeGauge(MeterRegistry registry,
            StreamingSessionAgentCacheMetrics streamingSessionAgentMetrics) {
        return Gauge.builder("mcp.streaming.agent.cache.size",
                streamingSessionAgentMetrics, StreamingSessionAgentCacheMetrics::getCacheSize)
                .description("Current number of cached LangChain4j streaming agent sessions")
                .register(registry);
    }

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
        return Timer.builder("nlt.request.latency").register(registry);
    }

    @Bean
    public Timer nltPlanningLatencyMs(MeterRegistry registry) {
        return Timer.builder("nlt.planning.latency").register(registry);
    }

    @Bean
    public Timer nltExecutionLatencyMs(MeterRegistry registry) {
        return Timer.builder("nlt.execution.latency").register(registry);
    }

    @Bean
    public Timer toolExecutionTimer(MeterRegistry registry) {
        return Timer.builder("mcp.tool.execution.latency")
                .description("Latency of facade tool executions")
                .register(registry);
    }

    @Bean
    public Timer modelLatencyTimer(MeterRegistry registry) {
        return Timer.builder("mcp.model.latency")
                .description("Latency of Ollama LLM model invocations")
                .register(registry);
    }
}
