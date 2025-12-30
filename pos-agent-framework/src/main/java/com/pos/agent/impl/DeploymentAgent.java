package com.pos.agent.impl;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;

import java.util.List;

/**
 * DeploymentAgent
 */
public class DeploymentAgent implements Agent {
    
    @Override
    public AgentResponse processRequest(AgentRequest request) {
        if (request == null) {
            return createFailureResponse("Invalid request: request is null");
        }
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            return createFailureResponse("Invalid request: description is required");
        }
        if (request.getContext() == null) {
            return createFailureResponse("Invalid request: context is required");
        }
        if (request.getType() == null || request.getType().contains("invalid")) {
            return createFailureResponse("Invalid request: invalid type");
        }
        
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Deployment guidance: " + request.getDescription());
        response.setConfidence(0.8);
        response.setRecommendations(List.of("implement pattern", "configure system", "add monitoring"));
        return response;
    }
    
    private AgentResponse createFailureResponse(String message) {
        AgentResponse response = new AgentResponse();
        response.setStatus("FAILURE");
        response.setOutput(message);
        response.setConfidence(0.0);
        return response;
    }
}
