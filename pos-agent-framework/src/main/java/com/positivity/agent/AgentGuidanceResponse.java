package com.positivity.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response from an agent providing guidance
 */
public record AgentGuidanceResponse(
    String requestId,
    String agentId,
    String guidance,
    double confidence,
    List<String> recommendations,
    Map<String, Object> metadata,
    Instant timestamp,
    Duration processingTime,
    ResponseStatus status
) {
    
    public enum ResponseStatus {
        SUCCESS, PARTIAL_SUCCESS, FAILURE, TIMEOUT
    }
    
    public static AgentGuidanceResponse success(String requestId, String agentId, String guidance, 
                                             double confidence, List<String> recommendations, 
                                             Duration processingTime) {
        return new AgentGuidanceResponse(
            requestId,
            agentId,
            guidance,
            confidence,
            recommendations,
            Map.of(),
            Instant.now(),
            processingTime,
            ResponseStatus.SUCCESS
        );
    }
    
    public static AgentGuidanceResponse failure(String requestId, String agentId, String errorMessage, 
                                              Duration processingTime) {
        return new AgentGuidanceResponse(
            requestId,
            agentId,
            errorMessage,
            0.0,
            List.of(),
            Map.of("error", true),
            Instant.now(),
            processingTime,
            ResponseStatus.FAILURE
        );
    }
    
    public boolean isSuccessful() {
        return status == ResponseStatus.SUCCESS || status == ResponseStatus.PARTIAL_SUCCESS;
    }
}