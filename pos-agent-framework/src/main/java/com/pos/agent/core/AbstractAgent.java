package com.pos.agent.core;

import java.util.Collections;

/**
 * Abstract base class for all agents.
 * Centralizes validation and failure response creation,
 * allowing concrete agents to focus on domain logic.
 * Part of frozen contract specification (REQ-016)
 */
public abstract class AbstractAgent implements Agent {

    /**
     * Process an agent request and return response.
     * Performs validation before delegating to the implementation.
     * Template method for processing agent requests.
     * Validates the request and delegates to the concrete agent's handle method.
     * 
     * @param request The request to process
     * @return The agent response
     */
    @Override
    public final AgentResponse processRequest(AgentRequest request) {
        // Validate request
        String validationError = validateRequest(request);
        if (validationError != null) {
            return createFailureResponse(validationError);
        }
        
        // Delegate to implementation
        return doProcessRequest(request);
    }
    
    /**
     * Validate the request. Returns null if valid, error message otherwise.
     * 
     * @param request The request to validate
     * @return null if valid, error message otherwise
     */
    protected String validateRequest(AgentRequest request) {
        if (request == null) {
            return "Invalid request: request is null";
        }
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            return "Invalid request: description is required";
        }
        if (request.getAgentContext() == null) {
            return "Invalid request: agent context is required";
        }
        if (request.getType() == null || request.getType().contains("invalid")) {
            return "Invalid request: invalid type";
        }
        return null;
    }
    
    /**
     * Create a failure response with the given message.
     * 
     * @param message The error message
     * @return The failure response
     */
    protected AgentResponse createFailureResponse(String message) {
        return AgentResponse.builder()
                .status(AgentStatus.FAILURE)
                .output(message)
                .confidence(0.0)
                .success(false)
                .errorMessage(message)
                .recommendations(Collections.emptyList())
                .build();
    }
    
    /**
     * Process the request after validation.
     * Subclasses must implement this method to provide their specific logic.
     * 
     * @param request The validated request
     * @return The agent response
     */
    protected abstract AgentResponse doProcessRequest(AgentRequest request);
}
