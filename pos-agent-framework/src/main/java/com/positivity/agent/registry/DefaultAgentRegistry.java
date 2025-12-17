package com.positivity.agent.registry;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentHealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default implementation of the AgentRegistry
 */
@Component
public class DefaultAgentRegistry implements AgentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentRegistry.class);

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, List<String>> backupMappings = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> domainMappings = new ConcurrentHashMap<>();

    // NEW: Enhanced capability mappings for specialized agents (REQ-012.1,
    // REQ-013.1, REQ-014.1, REQ-015.1)
    private final Map<String, Set<String>> capabilityMappings = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> specializedDomainMappings = new ConcurrentHashMap<>();

    // NEW: Agent type classifications for better discovery
    private final Set<String> eventDrivenAgents = ConcurrentHashMap.newKeySet();
    private final Set<String> cicdAgents = ConcurrentHashMap.newKeySet();
    private final Set<String> configurationAgents = ConcurrentHashMap.newKeySet();
    private final Set<String> resilienceAgents = ConcurrentHashMap.newKeySet();

    @Override
    public void registerAgent(Agent agent) {
        logger.info("Registering agent: {} ({})", agent.getName(), agent.getId());
        agents.put(agent.getId(), agent);

        // Update domain mappings
        domainMappings.computeIfAbsent(agent.getDomain(), k -> new HashSet<>()).add(agent.getId());

        // NEW: Enhanced capability and specialized domain mappings (REQ-012.1,
        // REQ-013.1, REQ-014.1, REQ-015.1)
        updateCapabilityMappings(agent);
        updateSpecializedDomainMappings(agent);
        classifySpecializedAgent(agent);

        // Set up backup mappings based on domain similarity
        setupBackupMappings(agent);

        logger.info("Agent {} registered successfully. Total agents: {}", agent.getId(), agents.size());
    }

    @Override
    public void unregisterAgent(String agentId) {
        Agent removed = agents.remove(agentId);
        if (removed != null) {
            logger.info("Unregistered agent: {} ({})", removed.getName(), agentId);

            // Clean up domain mappings
            domainMappings.values().forEach(set -> set.remove(agentId));
            domainMappings.entrySet().removeIf(entry -> entry.getValue().isEmpty());

            // Clean up backup mappings
            backupMappings.remove(agentId);
            backupMappings.values().forEach(list -> list.remove(agentId));

            // NEW: Clean up enhanced capability and specialized domain mappings
            capabilityMappings.values().forEach(set -> set.remove(agentId));
            capabilityMappings.entrySet().removeIf(entry -> entry.getValue().isEmpty());

            specializedDomainMappings.values().forEach(set -> set.remove(agentId));
            specializedDomainMappings.entrySet().removeIf(entry -> entry.getValue().isEmpty());

            // Clean up agent type classifications
            eventDrivenAgents.remove(agentId);
            cicdAgents.remove(agentId);
            configurationAgents.remove(agentId);
            resilienceAgents.remove(agentId);
        }
    }

    @Override
    public Optional<Agent> getAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public List<Agent> getAllAgents() {
        return new ArrayList<>(agents.values());
    }

    @Override
    @Cacheable(value = "agentsByDomain", key = "#domain")
    public List<Agent> getAgentsForDomain(String domain) {
        Set<String> agentIds = domainMappings.getOrDefault(domain, Set.of());
        return agentIds.stream()
                .map(agents::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Agent> getAgentsWithCapabilities(Set<String> capabilities) {
        return agents.values().stream()
                .filter(agent -> agent.getCapabilities().stream()
                        .anyMatch(capabilities::contains))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Agent> findBestAgent(AgentConsultationRequest request) {
        Instant start = Instant.now();

        // First, try to find agents for the specific domain
        List<Agent> candidateAgents = getAgentsForDomain(request.domain());

        // If no domain-specific agents, look for agents with relevant capabilities
        if (candidateAgents.isEmpty()) {
            candidateAgents = agents.values().stream()
                    .filter(agent -> agent.canHandle(request))
                    .collect(Collectors.toList());
        }

        // Filter for available agents
        List<Agent> availableAgents = candidateAgents.stream()
                .filter(Agent::isAvailable)
                .collect(Collectors.toList());

        if (availableAgents.isEmpty()) {
            logger.warn("No available agents found for domain: {}", request.domain());
            return Optional.empty();
        }

        // Select the best agent based on performance metrics and load
        Agent bestAgent = availableAgents.stream()
                .min(Comparator
                        .comparing((Agent a) -> a.getMetrics().activeRequests())
                        .thenComparing(a -> a.getMetrics().averageResponseTime())
                        .thenComparing(a -> -a.getMetrics().currentAccuracy()))
                .orElse(availableAgents.get(0));

        Duration selectionTime = Duration.between(start, Instant.now());
        if (selectionTime.compareTo(Duration.ofSeconds(1)) > 0) {
            logger.warn("Agent selection took longer than expected: {}", selectionTime);
        }

        logger.debug("Selected agent {} for request {} in {}",
                bestAgent.getId(), request.requestId(), selectionTime);

        return Optional.of(bestAgent);
    }

    @Override
    public List<Agent> getAvailableAgents() {
        return agents.values().stream()
                .filter(Agent::isAvailable)
                .collect(Collectors.toList());
    }

    @Override
    public List<Agent> getBackupAgents(String agentId) {
        List<String> backupIds = backupMappings.getOrDefault(agentId, List.of());
        return backupIds.stream()
                .map(agents::get)
                .filter(Objects::nonNull)
                .filter(Agent::isAvailable)
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<AgentGuidanceResponse> consultBestAgent(AgentConsultationRequest request) {
        Optional<Agent> bestAgent = findBestAgent(request);

        if (bestAgent.isEmpty()) {
            return CompletableFuture.completedFuture(
                    AgentGuidanceResponse.failure(request.requestId(), "registry",
                            "No available agents found for domain: " + request.domain(), Duration.ZERO));
        }

        Agent agent = bestAgent.get();

        return agent.provideGuidance(request)
                .exceptionally(throwable -> {
                    logger.error("Agent {} failed to provide guidance for request {}",
                            agent.getId(), request.requestId(), throwable);

                    // Try backup agents
                    List<Agent> backupAgents = getBackupAgents(agent.getId());
                    if (!backupAgents.isEmpty()) {
                        logger.info("Attempting failover to backup agent for request {}", request.requestId());
                        Agent backupAgent = backupAgents.get(0);
                        try {
                            return backupAgent.provideGuidance(request).get();
                        } catch (Exception e) {
                            logger.error("Backup agent {} also failed", backupAgent.getId(), e);
                        }
                    }

                    return AgentGuidanceResponse.failure(request.requestId(), agent.getId(),
                            "Agent and backup agents failed: " + throwable.getMessage(), Duration.ZERO);
                });
    }

    @Override
    public RegistryHealthStatus getHealthStatus() {
        int total = agents.size();
        int available = (int) agents.values().stream().filter(Agent::isAvailable).count();
        int unhealthy = (int) agents.values().stream()
                .filter(agent -> agent.getHealthStatus().state() == AgentHealthStatus.HealthState.UNHEALTHY)
                .count();

        double availability = total > 0 ? (double) available / total : 1.0;

        return RegistryHealthStatus.create(total, available, unhealthy, availability);
    }

    private void setupBackupMappings(Agent agent) {
        // Find potential backup agents in the same domain or with overlapping
        // capabilities
        List<String> backups = agents.values().stream()
                .filter(other -> !other.getId().equals(agent.getId()))
                .filter(other -> other.getDomain().equals(agent.getDomain()) ||
                        hasOverlappingCapabilities(agent, other))
                .map(Agent::getId)
                .limit(3) // Limit to 3 backup agents
                .collect(Collectors.toList());

        if (!backups.isEmpty()) {
            backupMappings.put(agent.getId(), backups);
            logger.debug("Set up backup agents for {}: {}", agent.getId(), backups);
        }
    }

    private boolean hasOverlappingCapabilities(Agent agent1, Agent agent2) {
        return agent1.getCapabilities().stream()
                .anyMatch(agent2.getCapabilities()::contains);
    }

    /**
     * Updates capability mappings for enhanced agent discovery (REQ-012.1,
     * REQ-013.1, REQ-014.1, REQ-015.1)
     */
    private void updateCapabilityMappings(Agent agent) {
        // Map each capability to the agent ID for fast lookup
        for (String capability : agent.getCapabilities()) {
            capabilityMappings.computeIfAbsent(capability, k -> ConcurrentHashMap.newKeySet()).add(agent.getId());
        }

        logger.debug("Updated capability mappings for agent {}: {}", agent.getId(), agent.getCapabilities());
    }

    /**
     * Updates specialized domain mappings for new agent types (REQ-012.1,
     * REQ-013.1, REQ-014.1, REQ-015.1)
     */
    private void updateSpecializedDomainMappings(Agent agent) {
        // Create specialized domain mappings based on agent capabilities
        Set<String> capabilities = agent.getCapabilities();

        // Event-driven domain mappings
        if (capabilities.contains("event-schemas") || capabilities.contains("kafka") ||
                capabilities.contains("sns-sqs") || capabilities.contains("rabbitmq")) {
            specializedDomainMappings.computeIfAbsent("event-driven", k -> ConcurrentHashMap.newKeySet())
                    .add(agent.getId());
        }

        // CI/CD domain mappings
        if (capabilities.contains("build-automation") || capabilities.contains("deployment-strategies") ||
                capabilities.contains("security-scanning") || capabilities.contains("pipeline-orchestration")) {
            specializedDomainMappings.computeIfAbsent("cicd", k -> ConcurrentHashMap.newKeySet()).add(agent.getId());
        }

        // Configuration management domain mappings
        if (capabilities.contains("spring-cloud-config") || capabilities.contains("feature-flags") ||
                capabilities.contains("secrets-management") || capabilities.contains("configuration-validation")) {
            specializedDomainMappings.computeIfAbsent("configuration", k -> ConcurrentHashMap.newKeySet())
                    .add(agent.getId());
        }

        // Resilience engineering domain mappings
        if (capabilities.contains("circuit-breakers") || capabilities.contains("retry-patterns") ||
                capabilities.contains("bulkhead-patterns") || capabilities.contains("chaos-engineering")) {
            specializedDomainMappings.computeIfAbsent("resilience", k -> ConcurrentHashMap.newKeySet())
                    .add(agent.getId());
        }

        logger.debug("Updated specialized domain mappings for agent {}", agent.getId());
    }

    /**
     * Classifies agents into specialized types for enhanced discovery (REQ-012.1,
     * REQ-013.1, REQ-014.1, REQ-015.1)
     */
    private void classifySpecializedAgent(Agent agent) {
        Set<String> capabilities = agent.getCapabilities();
        String agentId = agent.getId();

        // Classify event-driven agents
        if (capabilities.contains("event-schemas") || capabilities.contains("kafka") ||
                capabilities.contains("event-sourcing") || agentId.contains("event-driven")) {
            eventDrivenAgents.add(agentId);
            logger.debug("Classified {} as event-driven agent", agentId);
        }

        // Classify CI/CD agents
        if (capabilities.contains("build-automation") || capabilities.contains("deployment-strategies") ||
                capabilities.contains("security-scanning") || agentId.contains("cicd")) {
            cicdAgents.add(agentId);
            logger.debug("Classified {} as CI/CD agent", agentId);
        }

        // Classify configuration management agents
        if (capabilities.contains("spring-cloud-config") || capabilities.contains("feature-flags") ||
                capabilities.contains("secrets-management") || agentId.contains("configuration")) {
            configurationAgents.add(agentId);
            logger.debug("Classified {} as configuration management agent", agentId);
        }

        // Classify resilience engineering agents
        if (capabilities.contains("circuit-breakers") || capabilities.contains("retry-patterns") ||
                capabilities.contains("chaos-engineering") || agentId.contains("resilience")) {
            resilienceAgents.add(agentId);
            logger.debug("Classified {} as resilience engineering agent", agentId);
        }
    }
}