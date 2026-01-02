package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Filters agents based on health status.
 * Acts as a secondary filter to ensure only healthy agents are selected.
 * 
 * Priority: LOW - Applied after primary discovery strategies.
 */
public class HealthBasedDiscoveryStrategy implements AgentDiscoveryStrategy {

    private static final int PRIORITY = 10;

    @Override
    public boolean canHandle(AgentRequest request) {
        // This strategy always applies as a health check
        return true;
    }

    @Override
    public CompletableFuture<Optional<Agent>> discoverBestAgent(
            AgentRequest request,
            List<Agent> availableAgents) {

        return CompletableFuture.supplyAsync(() -> {
            // Return first healthy agent (should be used after other strategies)
            return availableAgents.stream()
                    .filter(Agent::isHealthy)
                    .findFirst();
        });
    }

    /**
     * Filters agents to only healthy ones (post-processing step).
     */
    public List<Agent> filterHealthyAgents(List<Agent> agents) {
        return agents.stream()
                .filter(Agent::isHealthy)
                .collect(Collectors.toList());
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }
}
