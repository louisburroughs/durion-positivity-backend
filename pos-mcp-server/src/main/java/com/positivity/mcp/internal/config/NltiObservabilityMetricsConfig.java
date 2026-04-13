package com.positivity.mcp.internal.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.Method;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class NltiObservabilityMetricsConfig {

    @Bean
    @Profile("alpha")
    public Gauge agentCacheSizeGauge(MeterRegistry registry,
            Object sessionAgentManager) {
        return Gauge.builder("mcp.agent.cache.size",
                sessionAgentManager, this::resolveCacheSize)
                .description("Current number of cached LangChain4j agent sessions")
                .register(registry);
    }

    @Bean
    @Profile("alpha")
    public Gauge streamingAgentCacheSizeGauge(MeterRegistry registry,
            Object streamingSessionAgentManager) {
        return Gauge.builder("mcp.streaming.agent.cache.size",
                streamingSessionAgentManager, this::resolveCacheSize)
                .description("Current number of cached LangChain4j streaming agent sessions")
                .register(registry);
    }

    private double resolveCacheSize(Object sessionAgentManager) {
        try {
            Method getter = sessionAgentManager.getClass().getMethod("getCacheSize");
            Object result = getter.invoke(sessionAgentManager);
            if (result instanceof Number number) {
                return number.doubleValue();
            }
        } catch (ReflectiveOperationException _) {
            // Keep gauge resilient if the bean contract changes.
        }
        return 0D;
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