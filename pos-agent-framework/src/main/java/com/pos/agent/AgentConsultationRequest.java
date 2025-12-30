package com.pos.agent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Record representing a request for agent consultation.
 * Encapsulates the details needed for agents to provide guidance.
 */
public record AgentConsultationRequest(
        String requestId,
        String agentId,
        String query,
        Map<String, Object> context,
        String requestType,
        Priority priority,
        Instant timestamp) {

    public enum Priority {
        LOW, NORMAL, HIGH, URGENT
    }

    /**
     * Factory method to create a consultation request.
     *
     * @param agentId     Target agent identifier
     * @param query       Query or request description
     * @param context     Context information as key-value pairs
     * @param requestType Type of request (e.g., "integration", "architecture")
     * @param priority    Request priority level
     * @return New consultation request instance
     */
    public static AgentConsultationRequest create(
            String agentId,
            String query,
            Map<String, Object> context,
            String requestType,
            Priority priority) {
        return new AgentConsultationRequest(
                UUID.randomUUID().toString(),
                agentId,
                query,
                context,
                requestType,
                priority,
                Instant.now());
    }
}
