package com.positivity.positivity.agent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a consultation request to an agent
 */
public record AgentConsultationRequest(
    String requestId,
    String domain,
    String query,
    Map<String, Object> context,
    String requesterId,
    Instant timestamp,
    Priority priority
) {
    
    public enum Priority {
        LOW, NORMAL, HIGH, CRITICAL
    }
    
    public static AgentConsultationRequest create(String domain, String query, Map<String, Object> context) {
        return new AgentConsultationRequest(
            UUID.randomUUID().toString(),
            domain,
            query,
            context,
            "system", // Default requester
            Instant.now(),
            Priority.NORMAL
        );
    }
    
    public static AgentConsultationRequest create(String domain, String query, Map<String, Object> context, 
                                                String requesterId, Priority priority) {
        return new AgentConsultationRequest(
            UUID.randomUUID().toString(),
            domain,
            query,
            context,
            requesterId,
            Instant.now(),
            priority
        );
    }
}