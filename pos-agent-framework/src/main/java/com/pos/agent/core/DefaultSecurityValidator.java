package com.pos.agent.core;

/**
 * Default implementation of SecurityValidator that delegates to SecurityValidation singleton.
 * Maintains existing production behavior while allowing for testability via dependency injection.
 */
public class DefaultSecurityValidator implements SecurityValidator {
    
    @Override
    public String extractUserId(AgentRequest request) {
        return SecurityValidation.getInstance().extractUserId(request);
    }
    
    @Override
    public boolean validateSecurityContext(SecurityContext securityContext) {
        return SecurityValidation.getInstance().validateSecurityContext(securityContext);
    }
    
    @Override
    public boolean validateAuthorization(AgentRequest request, Agent agent) {
        return SecurityValidation.getInstance().validateAuthorization(request, agent);
    }
}
