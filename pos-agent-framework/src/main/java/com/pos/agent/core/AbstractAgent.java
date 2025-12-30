package com.pos.agent.core;

/**
 * Abstract base class for all agents.
 * Centralizes validation and failure response creation,
 * allowing concrete agents to focus on domain logic.
 * Part of frozen contract specification (REQ-016)
 */
public abstract class AbstractAgent implements Agent {

    /**
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

        // Delegate to concrete implementation
        try {
            return handle(request);
        } catch (Exception e) {
            return createFailureResponse("Internal error: " + e.getMessage());
        }
    }

    /**
     * Validates the agent request.
     * Subclasses can override to add additional validation.
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
        if (request.getContext() == null) {
            return "Invalid request: context is required";
        }
        if (request.getType() == null || request.getType().contains("invalid")) {
            return "Invalid request: invalid type";
        }
        return null;
    }

    /**
     * Creates a failure response with the given error message.
     * 
     * @param message The error message
     * @return A failure response
     */
    protected AgentResponse createFailureResponse(String message) {
        return AgentResponse.builder()
                .status(AgentStatus.FAILURE)
                .output(message)
                .confidence(0.0)
                .build();
    }

    /**
     * Handles the validated agent request.
     * Concrete agents must implement this method to provide their domain logic.
     * 
     * @param request The validated request
     * @return The agent response
     */
    protected abstract AgentResponse handle(AgentRequest request);
}
