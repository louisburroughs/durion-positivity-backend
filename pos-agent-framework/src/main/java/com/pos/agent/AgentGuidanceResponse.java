package com.pos.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Record representing a guidance response from an agent.
 * Contains the guidance, recommendations, and metadata about the response.
 */
public record AgentGuidanceResponse(
        String responseId,
        String requestId,
        String agentId,
        String guidance,
        double confidence,
        List<String> recommendations,
        Duration processingTime,
        Instant timestamp) {

    /**
     * Factory method to create a successful guidance response.
     *
     * @param requestId       Original request identifier
     * @param agentId         Agent identifier
     * @param guidance        Guidance text
     * @param confidence      Confidence level (0.0 to 1.0)
     * @param recommendations List of recommendations
     * @param processingTime  Time taken to process
     * @return New guidance response instance
     */
    public static AgentGuidanceResponse success(
            String requestId,
            String agentId,
            String guidance,
            double confidence,
            List<String> recommendations,
            Duration processingTime) {
        return new AgentGuidanceResponse(
                UUID.randomUUID().toString(),
                requestId,
                agentId,
                guidance,
                confidence,
                recommendations,
                processingTime,
                Instant.now());
    }
}
