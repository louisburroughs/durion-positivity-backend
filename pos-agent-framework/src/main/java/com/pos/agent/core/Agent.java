package com.pos.agent.core;

import java.util.List;
import java.util.Map;

/**
 * Base interface for all agents in the system.
 * Defines the contract that all agent implementations must follow.
 */
public interface Agent {

    /**
     * Gets the type of this agent.
     * 
     * @return the agent type
     */
    AgentType getType();

    /**
     * Gets the display name of this agent.
     * 
     * @return the display name
     */
    String getName();

    /**
     * Gets the version of this agent.
     * 
     * @return the version string
     */
    String getVersion();

    /**
     * Gets the capabilities of this agent.
     * 
     * @return list of capability descriptions
     */
    List<String> getCapabilities();

    /**
     * Processes a request and returns a response.
     * 
     * @param request the agent request to process
     * @return the agent response
     */
    AgentResponse processRequest(AgentRequest request);

    /**
     * Checks if this agent can handle the given request.
     * 
     * @param request the request to check
     * @return true if the agent can handle the request
     */
    boolean canHandle(AgentRequest request);

    /**
     * Gets the health status of this agent.
     * 
     * @return health status information
     */
    Map<String, Object> getHealthStatus();

    /**
     * Initializes the agent with configuration.
     * 
     * @param config configuration parameters
     */
    void initialize(Map<String, Object> config);

    /**
     * Shuts down the agent and cleans up resources.
     */
    void shutdown();
}