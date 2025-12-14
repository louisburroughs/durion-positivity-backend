package com.positivity.agent.collaboration;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Protocol for coordinating collaboration between multiple agents
 */
public interface AgentCollaborationProtocol {
    
    /**
     * Coordinate a multi-agent consultation
     * @param request The consultation request
     * @param participatingAgents List of agent IDs to participate
     * @return Future containing the collaborative response
     */
    CompletableFuture<CollaborativeGuidanceResponse> coordinateConsultation(
            AgentConsultationRequest request, List<String> participatingAgents);
    
    /**
     * Validate consistency between agent responses
     * @param responses List of responses from different agents
     * @return Consistency validation result
     */
    ConsistencyValidationResult validateConsistency(List<AgentGuidanceResponse> responses);
    
    /**
     * Resolve conflicts between agent recommendations
     * @param responses Conflicting responses
     * @return Resolved guidance response
     */
    AgentGuidanceResponse resolveConflicts(List<AgentGuidanceResponse> responses);
    
    /**
     * Get the collaboration workflow for a specific request type
     * @param requestDomain The domain of the request
     * @return Ordered list of agent IDs for the workflow
     */
    List<String> getCollaborationWorkflow(String requestDomain);
}