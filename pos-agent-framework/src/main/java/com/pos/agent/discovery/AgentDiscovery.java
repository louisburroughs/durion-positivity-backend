package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for agent discovery operations.
 * Allows dependency injection for testing without creating concrete discovery components.
 */
public interface AgentDiscovery {
    
    /**
     * Discovers the best agent for the given request from available agents.
     * 
     * @param request the agent request
     * @param availableAgents list of registered agents
     * @return CompletableFuture with the best agent, or empty if none match
     */
    CompletableFuture<Optional<Agent>> discoverBestAgent(AgentRequest request, List<Agent> availableAgents);
}
