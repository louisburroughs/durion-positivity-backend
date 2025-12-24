package com.positivity.agent.core;

/**
 * Base interface for all agents
 * Defines frozen contract (REQ-016)
 */
public interface Agent {
    /**
     * Process an agent request and return response
     * @param request The request to process
     * @return The agent response
     */
    AgentResponse processRequest(AgentRequest request);
}
