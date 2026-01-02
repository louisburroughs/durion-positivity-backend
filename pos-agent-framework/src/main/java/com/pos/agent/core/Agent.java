package com.pos.agent.core;

import java.util.List;

import com.pos.agent.framework.model.AgentType;

/**
 * Base interface for all agents
 * Defines frozen contract (REQ-016)
 */
public interface Agent {
    /**
     * Process an agent request and return response
     * 
     * @param request The request to process
     * @return The agent response
     */
    AgentResponse processRequest(AgentRequest request);

    /**
     * Get the current status of the agent
     * 
     * @return The agent status
     */
    AgentStatus getStatus();

    /**
     * Checks if the agent is in a healthy operational state.
     * 
     * @return {@code true} if the agent is healthy, {@code false} otherwise
     */
    boolean isHealthy();

    /**
     * Gets the list of capabilities this agent supports.
     * 
     * @return list of capability identifiers
     */
    List<String> getCapabilities();

    /**
     * Gets the technical domain this agent operates in.
     * 
     * @return the technical domain identifier
     */
    AgentType getTechnicalDomain();

    /**
     * Gets the list of roles required to access this agent.
     * Agents should override this to specify their authorization requirements.
     * 
     * @return list of required role names
     */
    List<String> getRequiredRoles();

    /**
     * Gets the list of permissions required to access this agent.
     * Agents should override this to specify their authorization requirements.
     * 
     * @return list of required permission names
     */
    List<String> getRequiredPermissions();

    String generateOutput(String query);
}
