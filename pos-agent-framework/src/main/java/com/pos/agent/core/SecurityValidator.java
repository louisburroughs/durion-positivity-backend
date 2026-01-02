package com.pos.agent.core;

/**
 * Interface for security validation operations.
 * Allows dependency injection for testing without static mocking.
 * 
 * Production implementations typically delegate to SecurityValidation.getInstance(),
 * while test implementations can provide mocks or stubs.
 */
public interface SecurityValidator {
    
    /**
     * Extracts the user ID from the security context in an agent request.
     * 
     * @param request the agent request containing security context
     * @return the user ID, or "unknown" if not available
     */
    String extractUserId(AgentRequest request);
    
    /**
     * Validates the security context by checking credentials and token validity.
     * 
     * @param securityContext the security context to validate
     * @return true if the security context is valid, false otherwise
     */
    boolean validateSecurityContext(SecurityContext securityContext);
    
    /**
     * Validates authorization for an agent request based on the agent's requirements.
     * 
     * @param request the agent request to validate
     * @param agent the agent being accessed
     * @return true if the request is authorized, false otherwise
     */
    boolean validateAuthorization(AgentRequest request, Agent agent);
}
