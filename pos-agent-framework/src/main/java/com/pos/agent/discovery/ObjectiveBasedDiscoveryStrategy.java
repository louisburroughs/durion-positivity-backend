package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers agents based on objective keywords and patterns in request
 * properties.
 * Analyzes objective text to determine the best agent for specialized tasks.
 * 
 * Priority: MEDIUM - Used when domain mapping is insufficient.
 */
public class ObjectiveBasedDiscoveryStrategy implements AgentDiscoveryStrategy {

    private static final int PRIORITY = 50;

    // Keyword patterns for different agent types
    private static final Map<String, List<Pattern>> OBJECTIVE_PATTERNS = Map.ofEntries(
            // Testing/Validation agents
            Map.entry("test", Arrays.asList(
                    Pattern.compile("test", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("validation", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("verify", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("story", Pattern.CASE_INSENSITIVE))),
            // Architecture agents
            Map.entry("architecture", Arrays.asList(
                    Pattern.compile("design", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("architecture", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("pattern", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("system.*design", Pattern.CASE_INSENSITIVE))),
            // Security agents
            Map.entry("security", Arrays.asList(
                    Pattern.compile("security", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("authentication", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("authorization", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("vulnerability", Pattern.CASE_INSENSITIVE))),
            // Performance agents
            Map.entry("performance", Arrays.asList(
                    Pattern.compile("performance", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("optimization", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("latency", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("throughput", Pattern.CASE_INSENSITIVE))),
            // Integration agents
            Map.entry("integration", Arrays.asList(
                    Pattern.compile("integration", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("gateway", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("interop", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("api.*gateway", Pattern.CASE_INSENSITIVE))));

    @Override
    public boolean canHandle(AgentRequest request) {
        AgentDiscoveryContext context = AgentDiscoveryContext.fromRequest(request);
        return context.getObjective().isPresent() ||
                hasObjectiveInProperties(context.getProperties());
    }

    @Override
    public CompletableFuture<Optional<Agent>> discoverBestAgent(
            AgentRequest request,
            List<Agent> availableAgents) {

        return CompletableFuture.supplyAsync(() -> {
            AgentDiscoveryContext context = AgentDiscoveryContext.fromRequest(request);

            // Extract objective text
            String objectiveText = context.getObjective()
                    .orElse(extractObjectiveFromProperties(context.getProperties()));

            if (objectiveText.isEmpty()) {
                return Optional.empty();
            }

            // Score agents based on keyword matches
            List<AgentScore> scoredAgents = scoreAgentsByObjective(
                    availableAgents,
                    objectiveText);

            // Return highest-scoring agent
            return scoredAgents.stream()
                    .filter(score -> score.score > 0)
                    .max(Comparator.comparingInt(score -> score.score))
                    .map(score -> score.agent);
        });
    }

    private List<AgentScore> scoreAgentsByObjective(List<Agent> agents, String objective) {
        return agents.stream()
                .map(agent -> new AgentScore(
                        agent,
                        calculateObjectiveScore(agent, objective)))
                .collect(Collectors.toList());
    }

    private int calculateObjectiveScore(Agent agent, String objective) {
        int score = 0;
        List<String> capabilities = agent.getCapabilities();

        if (capabilities == null) {
            return score;
        }

        for (Map.Entry<String, List<Pattern>> entry : OBJECTIVE_PATTERNS.entrySet()) {
            String capabilityType = entry.getKey();

            // Check if agent has this capability type
            boolean hasCapability = capabilities.stream()
                    .anyMatch(cap -> cap.contains(capabilityType));

            if (hasCapability) {
                // Check if objective matches patterns for this capability
                for (Pattern pattern : entry.getValue()) {
                    if (pattern.matcher(objective).find()) {
                        score += 10;
                    }
                }
            }
        }

        return score;
    }

    private boolean hasObjectiveInProperties(Map<String, Object> properties) {
        return properties.containsKey("objective") ||
                properties.containsKey("task") ||
                properties.containsKey("goal");
    }

    private String extractObjectiveFromProperties(Map<String, Object> properties) {
        return properties.getOrDefault("objective",
                properties.getOrDefault("task",
                        properties.getOrDefault("goal", "")))
                .toString();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    private static class AgentScore {
        Agent agent;
        int score;

        AgentScore(Agent agent, int score) {
            this.agent = agent;
            this.score = score;
        }
    }
}
