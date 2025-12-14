package com.positivity.positivity.agent;

import java.time.Duration;

/**
 * Performance specifications for an agent
 */
public record AgentPerformanceSpec(
    Duration responseTime,
    double accuracyThreshold,
    double availabilityThreshold,
    int maxConcurrentRequests,
    Duration cacheTimeout
) {
    
    public static AgentPerformanceSpec defaultSpec() {
        return new AgentPerformanceSpec(
            Duration.ofSeconds(3),  // Default 3-second response time
            0.95,                   // 95% accuracy threshold
            0.999,                  // 99.9% availability threshold
            100,                    // Max 100 concurrent requests
            Duration.ofMinutes(5)   // 5-minute cache timeout
        );
    }
    
    public static AgentPerformanceSpec highPerformanceSpec() {
        return new AgentPerformanceSpec(
            Duration.ofSeconds(2),  // 2-second response time
            0.96,                   // 96% accuracy threshold
            0.999,                  // 99.9% availability threshold
            200,                    // Max 200 concurrent requests
            Duration.ofMinutes(10)  // 10-minute cache timeout
        );
    }
    
    public static AgentPerformanceSpec criticalSpec() {
        return new AgentPerformanceSpec(
            Duration.ofSeconds(1),  // 1-second response time
            1.0,                    // 100% accuracy threshold
            0.999,                  // 99.9% availability threshold
            50,                     // Max 50 concurrent requests (quality over quantity)
            Duration.ofMinutes(1)   // 1-minute cache timeout
        );
    }
}