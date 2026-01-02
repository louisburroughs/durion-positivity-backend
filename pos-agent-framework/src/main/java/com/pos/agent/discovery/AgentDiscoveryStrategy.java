package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy pattern for agent discovery logic.
 * Encapsulates the decision-making process for selecting the best agent
 * for a given request, separate from AgentManager concerns.
 * 
 * Supports composition of multiple discovery strategies and fallback
 * mechanisms.
 */
public interface AgentDiscoveryStrategy {

    /**
     * Evaluates if this strategy can handle the request.
     */
    boolean canHandle(AgentRequest request);

    /**
     * Discovers the best agent for the given request.
     * 
     * @param request         the agent request
     * @param availableAgents list of agents available for selection
     * @return CompletableFuture with the selected agent, or empty if no match
     */
    CompletableFuture<Optional<Agent>> discoverBestAgent(
            AgentRequest request,
            List<Agent> availableAgents);

    /**
     * Gets the priority of this strategy (higher = evaluated first).
     */
    int getPriority();
}
