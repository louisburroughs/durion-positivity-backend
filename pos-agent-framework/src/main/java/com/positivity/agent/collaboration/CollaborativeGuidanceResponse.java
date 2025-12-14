package com.positivity.agent.collaboration;

import com.positivity.agent.AgentGuidanceResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response from a collaborative consultation involving multiple agents
 */
public record CollaborativeGuidanceResponse(
    String requestId,
    List<String> participatingAgents,
    String consolidatedGuidance,
    double overallConfidence,
    List<String> consolidatedRecommendations,
    List<AgentGuidanceResponse> individualResponses,
    ConsistencyValidationResult consistencyResult,
    Map<String, Object> collaborationMetadata,
    Instant timestamp,
    Duration totalProcessingTime,
    CollaborationStatus status
) {
    
    public enum CollaborationStatus {
        SUCCESS, PARTIAL_SUCCESS, CONSISTENCY_ISSUES, FAILURE
    }
    
    public static CollaborativeGuidanceResponse success(
            String requestId,
            List<String> participatingAgents,
            String consolidatedGuidance,
            double confidence,
            List<String> recommendations,
            List<AgentGuidanceResponse> responses,
            ConsistencyValidationResult consistency,
            Duration processingTime) {
        
        return new CollaborativeGuidanceResponse(
            requestId,
            participatingAgents,
            consolidatedGuidance,
            confidence,
            recommendations,
            responses,
            consistency,
            Map.of("collaboration_type", "multi_agent"),
            Instant.now(),
            processingTime,
            CollaborationStatus.SUCCESS
        );
    }
    
    public static CollaborativeGuidanceResponse failure(
            String requestId,
            List<String> participatingAgents,
            String errorMessage,
            Duration processingTime) {
        
        return new CollaborativeGuidanceResponse(
            requestId,
            participatingAgents,
            errorMessage,
            0.0,
            List.of(),
            List.of(),
            ConsistencyValidationResult.failed("Collaboration failed"),
            Map.of("error", true),
            Instant.now(),
            processingTime,
            CollaborationStatus.FAILURE
        );
    }
    
    public boolean isSuccessful() {
        return status == CollaborationStatus.SUCCESS || status == CollaborationStatus.PARTIAL_SUCCESS;
    }
    
    public boolean hasConsistencyIssues() {
        return !consistencyResult.isConsistent();
    }
}