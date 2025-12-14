package com.positivity.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * Performance metrics for an agent
 */
public record AgentMetrics(
    String agentId,
    long totalRequests,
    long successfulRequests,
    long failedRequests,
    Duration averageResponseTime,
    Duration maxResponseTime,
    double currentAccuracy,
    double availability,
    int activeRequests,
    Instant lastUpdated
) {
    
    public double getSuccessRate() {
        if (totalRequests == 0) return 1.0;
        return (double) successfulRequests / totalRequests;
    }
    
    public double getFailureRate() {
        return 1.0 - getSuccessRate();
    }
    
    public boolean meetsPerformanceSpec(AgentPerformanceSpec spec) {
        return averageResponseTime.compareTo(spec.responseTime()) <= 0 &&
               currentAccuracy >= spec.accuracyThreshold() &&
               availability >= spec.availabilityThreshold() &&
               activeRequests <= spec.maxConcurrentRequests();
    }
    
    public static AgentMetrics initial(String agentId) {
        return new AgentMetrics(
            agentId,
            0L,
            0L,
            0L,
            Duration.ZERO,
            Duration.ZERO,
            1.0,
            1.0,
            0,
            Instant.now()
        );
    }
}