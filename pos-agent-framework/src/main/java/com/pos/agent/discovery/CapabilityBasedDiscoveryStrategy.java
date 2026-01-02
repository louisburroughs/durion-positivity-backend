package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Discovers agents based on required capabilities extracted from request
 * properties.
 * Matches agents against explicitly required capabilities.
 * 
 * Priority: MEDIUM-LOW - Used when specific capabilities are required.
 */
public class CapabilityBasedDiscoveryStrategy implements AgentDiscoveryStrategy {

    private static final int PRIORITY = 40;
    private static final String REQUIRED_CAPABILITIES_KEY = "required-capabilities";

    @Override
    public boolean canHandle(AgentRequest request) {
        AgentDiscoveryContext context = AgentDiscoveryContext.fromRequest(request);
        return context.getProperty(REQUIRED_CAPABILITIES_KEY, List.class).isPresent();
    }

    @Override
    public CompletableFuture<Optional<Agent>> discoverBestAgent(
            AgentRequest request,
            List<Agent> availableAgents) {

        return CompletableFuture.supplyAsync(() -> {
            AgentDiscoveryContext context = AgentDiscoveryContext.fromRequest(request);

            Optional<List> requiredCaps = context.getProperty(
                    REQUIRED_CAPABILITIES_KEY,
                    List.class);

            if (requiredCaps.isEmpty()) {
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            List<String> requiredCapabilities = (List<String>) (List<?>) requiredCaps.get();
            Set<String> required = new HashSet<>(requiredCapabilities);

            // Find agent with best capability match
            return availableAgents.stream()
                    .map(agent -> new AgentCapabilityScore(
                            agent,
                            calculateCapabilityScore(agent, required)))
                    .filter(score -> score.score > 0)
                    .max(Comparator.comparingInt(score -> score.score))
                    .map(score -> score.agent);
        });
    }

    private int calculateCapabilityScore(Agent agent, Set<String> required) {
        List<String> agentCapabilities = agent.getCapabilities();

        if (agentCapabilities == null || agentCapabilities.isEmpty()) {
            return 0;
        }

        long matchCount = agentCapabilities.stream()
                .filter(required::contains)
                .count();

        // Score: number of matches * 10
        return (int) (matchCount * 10);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    private static class AgentCapabilityScore {
        Agent agent;
        int score;

        AgentCapabilityScore(Agent agent, int score) {
            this.agent = agent;
            this.score = score;
        }
    }
}
