package com.positivity.agent;

import java.time.Instant;
import java.util.Map;

/**
 * Health status information for an agent
 */
public record AgentHealthStatus(
    String agentId,
    HealthState state,
    String message,
    Instant lastCheck,
    Map<String, Object> details
) {
    
    public enum HealthState {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }
    
    public static AgentHealthStatus healthy(String agentId) {
        return new AgentHealthStatus(
            agentId,
            HealthState.HEALTHY,
            "Agent is operating normally",
            Instant.now(),
            Map.of()
        );
    }
    
    public static AgentHealthStatus degraded(String agentId, String reason) {
        return new AgentHealthStatus(
            agentId,
            HealthState.DEGRADED,
            reason,
            Instant.now(),
            Map.of("degradation_reason", reason)
        );
    }
    
    public static AgentHealthStatus unhealthy(String agentId, String reason) {
        return new AgentHealthStatus(
            agentId,
            HealthState.UNHEALTHY,
            reason,
            Instant.now(),
            Map.of("failure_reason", reason)
        );
    }
    
    public boolean isAvailable() {
        return state == HealthState.HEALTHY || state == HealthState.DEGRADED;
    }
}