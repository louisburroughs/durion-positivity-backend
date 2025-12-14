package com.positivity.agent;

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
    
    public static AgentConsultationRequest create(String domain, String query, Map<String, ?> context) {
        Map<String, Object> objectMap = new java.util.HashMap<>(context);
        return new AgentConsultationRequest(
            UUID.randomUUID().toString(),
            domain,
            query,
            objectMap,
            "system", // Default requester
            Instant.now(),
            Priority.NORMAL
        );
    }
    
    public static AgentConsultationRequest create(String domain, String query, Map<String, ?> context, 
                                                String requesterId, Priority priority) {
        Map<String, Object> objectMap = new java.util.HashMap<>(context);
        return new AgentConsultationRequest(
            UUID.randomUUID().toString(),
            domain,
            query,
            objectMap,
            requesterId,
            Instant.now(),
            priority
        );
    }
}