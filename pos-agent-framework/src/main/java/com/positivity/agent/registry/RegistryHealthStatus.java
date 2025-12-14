package com.positivity.agent.registry;

import java.time.Instant;
import java.util.Map;

/**
 * Health status information for the agent registry
 */
public record RegistryHealthStatus(
    int totalAgents,
    int availableAgents,
    int unhealthyAgents,
    double overallAvailability,
    Instant lastCheck,
    Map<String, Object> details
) {
    
    public boolean isHealthy() {
        return availableAgents > 0 && overallAvailability >= 0.8; // 80% availability threshold
    }
    
    public static RegistryHealthStatus create(int total, int available, int unhealthy, double availability) {
        return new RegistryHealthStatus(
            total,
            available,
            unhealthy,
            availability,
            Instant.now(),
            Map.of(
                "healthy_agents", total - unhealthy,
                "availability_percentage", availability * 100
            )
        );
    }
}