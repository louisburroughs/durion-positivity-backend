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
    
    @Override
    public void registerAgent(Agent agent) {
        logger.info("Registering agent: {} ({})", agent.getName(), agent.getId());
        agents.put(agent.getId(), agent);
        
        // Update domain mappings
        domainMappings.computeIfAbsent(agent.getDomain(), k -> new HashSet<>()).add(agent.getId());
        
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
                    "No available agents found for domain: " + request.domain(), Duration.ZERO)
            );
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
        // Find potential backup agents in the same domain or with overlapping capabilities
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
}