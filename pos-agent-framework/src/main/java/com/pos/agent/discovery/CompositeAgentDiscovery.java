package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Composite discovery engine that applies multiple strategies in priority
 * order.
 * Implements chain-of-responsibility pattern with fallback mechanisms.
 * 
 * Execution flow:
 * 1. Applies strategies in descending priority order
 * 2. Returns first match from highest-priority applicable strategy
 * 3. Falls back to lower-priority strategies if no match
 * 4. Applies health checks on selected agent
 */
public class CompositeAgentDiscovery implements AgentDiscovery {

    private final List<AgentDiscoveryStrategy> strategies;
    private final HealthBasedDiscoveryStrategy healthStrategy;

    public CompositeAgentDiscovery() {
        this.strategies = new ArrayList<>();
        this.healthStrategy = new HealthBasedDiscoveryStrategy();
    }

    /**
     * Registers a discovery strategy.
     */
    public void registerStrategy(AgentDiscoveryStrategy strategy) {
        Objects.requireNonNull(strategy);
        strategies.add(strategy);
        sortStrategiesByPriority();
    }

    /**
     * Discovers the best agent for the given request.
     * Applies strategies in priority order until a match is found.
     * 
     * @param request         the agent request
     * @param availableAgents list of registered agents
     * @return CompletableFuture with the best agent, or empty if none match
     */
    public CompletableFuture<Optional<Agent>> discoverBestAgent(
            AgentRequest request,
            List<Agent> availableAgents) {

        // Filter only healthy agents for discovery
        List<Agent> healthyAgents = healthStrategy.filterHealthyAgents(availableAgents);

        if (healthyAgents.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Try each strategy in priority order
        return applyStrategiesSequentially(request, healthyAgents, 0);
    }

    private CompletableFuture<Optional<Agent>> applyStrategiesSequentially(
            AgentRequest request,
            List<Agent> availableAgents,
            int strategyIndex) {

        // Base case: no more strategies
        if (strategyIndex >= strategies.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        AgentDiscoveryStrategy strategy = strategies.get(strategyIndex);

        // Skip strategies that cannot handle this request
        if (!strategy.canHandle(request)) {
            return applyStrategiesSequentially(request, availableAgents, strategyIndex + 1);
        }

        // Apply current strategy
        return strategy.discoverBestAgent(request, availableAgents)
                .thenCompose(result -> {
                    // If found, return it
                    if (result.isPresent()) {
                        return CompletableFuture.completedFuture(result);
                    }
                    // Otherwise, try next strategy
                    return applyStrategiesSequentially(request, availableAgents, strategyIndex + 1);
                });
    }

    /**
     * Gets strategies sorted by priority (highest first).
     */
    public List<AgentDiscoveryStrategy> getStrategiesByPriority() {
        return strategies.stream()
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .collect(Collectors.toList());
    }

    private void sortStrategiesByPriority() {
        strategies.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    /**
     * Gets count of registered strategies.
     */
    public int getStrategyCount() {
        return strategies.size();
    }
}
