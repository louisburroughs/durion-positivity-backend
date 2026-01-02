package com.pos.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ContextCoordinator {
    /**
     * Validates if request context is sufficient.
     */
    public ContextValidationResult validateContext(AgentRequest request);

    /**
     * Update session progress with task objectives and decisions.
     */
    public void updateSessionProgress(String sessionId, String taskObjective,
            Map<String, Object> decisions, List<String> nextSteps);

    /**
     * Get session context.
     */
    public Optional<SessionContext> getSessionContext(String sessionId);

    /**
     * Update specialized context from agent guidance.
     */
    public void updateSpecializedContext(String sessionId, Agent agent, AgentResponse response);

    /**
     * Get shared context for a specific agent.
     */
    public Map<String, Object> getSharedContextForAgent(String sessionId, Agent agent);

    /**
     * Enhance guidance with context information.
     */
    public AgentResponse enhanceWithContext(AgentResponse response,
            AgentRequest request, Agent agent);

    /**
     * Cleanup stale contexts.
     */
    public void cleanupStaleContexts();

    /**
     * Archive session context.
     */
    public void archiveSessionContext(String sessionId, Agent agent);
}
