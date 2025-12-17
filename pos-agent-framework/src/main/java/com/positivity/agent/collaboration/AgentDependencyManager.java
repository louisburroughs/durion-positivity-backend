package com.positivity.agent.collaboration;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages hierarchical dependencies between agents and coordinates cross-domain
 * collaboration
 * Implements REQ-001.3, REQ-005.1, REQ-011.5
 */
@Component
public class AgentDependencyManager {

    private static final Logger logger = LoggerFactory.getLogger(AgentDependencyManager.class);

    // Agent dependency graph: agent -> list of dependent agents
    private final Map<String, Set<String>> dependencies = new ConcurrentHashMap<>();

    // Agent hierarchy levels: agent -> hierarchy level (0 = core, higher = more
    // specialized)
    private final Map<String, Integer> hierarchyLevels = new ConcurrentHashMap<>();

    // Cross-domain coordination rules: domain -> list of supporting domains
    private final Map<String, Set<String>> crossDomainRules = new ConcurrentHashMap<>();

    // Agent priorities: agent -> priority level (higher = more priority)
    private final Map<String, Integer> agentPriorities = new ConcurrentHashMap<>();

    public AgentDependencyManager() {
        initializeHierarchicalDependencies();
        initializeCrossDomainRules();
        initializeAgentPriorities();
    }

    /**
     * Register an agent with its dependencies and hierarchy level
     */
    public void registerAgentDependencies(String agentId, Set<String> dependsOn, int hierarchyLevel, int priority) {
        dependencies.put(agentId, new HashSet<>(dependsOn));
        hierarchyLevels.put(agentId, hierarchyLevel);
        agentPriorities.put(agentId, priority);

        logger.debug("Registered agent {} with dependencies: {}, hierarchy level: {}, priority: {}",
                agentId, dependsOn, hierarchyLevel, priority);
    }

    /**
     * Get agents in dependency order for a consultation request
     */
    public List<String> getAgentsInDependencyOrder(AgentConsultationRequest request, List<String> candidateAgents) {
        // Filter candidates that exist in our dependency graph
        Set<String> validCandidates = candidateAgents.stream()
                .filter(dependencies::containsKey)
                .collect(Collectors.toSet());

        if (validCandidates.isEmpty()) {
            return candidateAgents; // Return original list if no dependency info available
        }

        // Perform topological sort based on dependencies
        List<String> orderedAgents = topologicalSort(validCandidates);

        // Add cross-domain coordination agents if needed
        Set<String> crossDomainAgents = getCrossDomainAgents(request.domain());
        crossDomainAgents.retainAll(validCandidates);

        // Merge cross-domain agents into the ordered list
        for (String crossDomainAgent : crossDomainAgents) {
            if (!orderedAgents.contains(crossDomainAgent)) {
                // Insert based on hierarchy level
                int insertIndex = findInsertionIndex(orderedAgents, crossDomainAgent);
                orderedAgents.add(insertIndex, crossDomainAgent);
            }
        }

        logger.debug("Dependency-ordered agents for request {}: {}", request.requestId(), orderedAgents);
        return orderedAgents;
    }

    /**
     * Get agents that should be consulted for cross-domain coordination
     */
    public Set<String> getCrossDomainAgents(String primaryDomain) {
        Set<String> supportingDomains = crossDomainRules.getOrDefault(primaryDomain, Set.of());
        Set<String> crossDomainAgents = new HashSet<>();

        for (String supportingDomain : supportingDomains) {
            // Find agents in supporting domains
            dependencies.keySet().stream()
                    .filter(agentId -> agentId.contains(supportingDomain))
                    .forEach(crossDomainAgents::add);
        }

        return crossDomainAgents;
    }

    /**
     * Get agents by priority order (highest priority first)
     */
    public List<String> getAgentsByPriority(Collection<String> agentIds) {
        return agentIds.stream()
                .sorted((a1, a2) -> Integer.compare(
                        agentPriorities.getOrDefault(a2, 0),
                        agentPriorities.getOrDefault(a1, 0)))
                .collect(Collectors.toList());
    }

    /**
     * Check if an agent has all its dependencies available
     */
    public boolean areDependenciesAvailable(String agentId, Set<String> availableAgents) {
        Set<String> requiredDependencies = dependencies.getOrDefault(agentId, Set.of());
        return availableAgents.containsAll(requiredDependencies);
    }

    /**
     * Get the hierarchy level of an agent
     */
    public int getHierarchyLevel(String agentId) {
        return hierarchyLevels.getOrDefault(agentId, Integer.MAX_VALUE);
    }

    /**
     * Get the priority of an agent
     */
    public int getAgentPriority(String agentId) {
        return agentPriorities.getOrDefault(agentId, 0);
    }

    /**
     * Validate that the dependency graph is acyclic
     */
    public boolean validateDependencyGraph() {
        try {
            topologicalSort(dependencies.keySet());
            return true;
        } catch (IllegalStateException e) {
            logger.error("Circular dependency detected in agent graph: {}", e.getMessage());
            return false;
        }
    }

    private List<String> topologicalSort(Set<String> agents) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> adjList = new HashMap<>();

        // Initialize in-degree and adjacency list
        for (String agent : agents) {
            inDegree.put(agent, 0);
            adjList.put(agent, new HashSet<>());
        }

        // Build adjacency list and calculate in-degrees
        for (String agent : agents) {
            Set<String> deps = dependencies.getOrDefault(agent, Set.of());
            for (String dep : deps) {
                if (agents.contains(dep)) {
                    adjList.get(dep).add(agent);
                    inDegree.put(agent, inDegree.get(agent) + 1);
                }
            }
        }

        // Kahn's algorithm for topological sorting
        Queue<String> queue = new LinkedList<>();
        for (String agent : agents) {
            if (inDegree.get(agent) == 0) {
                queue.offer(agent);
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);

            for (String neighbor : adjList.get(current)) {
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);
                if (inDegree.get(neighbor) == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        if (result.size() != agents.size()) {
            throw new IllegalStateException("Circular dependency detected in agent graph");
        }

        // Sort by hierarchy level within the topological order
        result.sort(Comparator.comparing(this::getHierarchyLevel));

        return result;
    }

    private int findInsertionIndex(List<String> orderedAgents, String agentToInsert) {
        int targetLevel = getHierarchyLevel(agentToInsert);

        for (int i = 0; i < orderedAgents.size(); i++) {
            if (getHierarchyLevel(orderedAgents.get(i)) > targetLevel) {
                return i;
            }
        }

        return orderedAgents.size();
    }

    private void initializeHierarchicalDependencies() {
        // Core agents (level 0) - no dependencies
        registerAgentDependencies("architecture-agent", Set.of(), 0, 100);
        registerAgentDependencies("implementation-agent", Set.of(), 0, 90);
        registerAgentDependencies("deployment-agent", Set.of(), 0, 80);
        registerAgentDependencies("testing-agent", Set.of(), 0, 70);

        // Specialized agents (level 1) - depend on core agents
        registerAgentDependencies("architectural-governance-agent",
                Set.of("architecture-agent"), 1, 85);
        registerAgentDependencies("integration-gateway-agent",
                Set.of("architecture-agent", "implementation-agent"), 1, 75);
        registerAgentDependencies("security-agent",
                Set.of("implementation-agent", "deployment-agent"), 1, 95);
        registerAgentDependencies("observability-agent",
                Set.of("deployment-agent", "implementation-agent"), 1, 65);
        registerAgentDependencies("documentation-agent",
                Set.of("architecture-agent", "implementation-agent"), 1, 50);
        registerAgentDependencies("business-domain-agent",
                Set.of("architecture-agent", "implementation-agent"), 1, 60);

        // Pair programming agent (level 2) - depends on implementation agent
        registerAgentDependencies("pair-navigator-agent",
                Set.of("implementation-agent"), 2, 80);
    }

    private void initializeCrossDomainRules() {
        // Security influences all domains
        crossDomainRules.put("implementation", Set.of("security"));
        crossDomainRules.put("deployment", Set.of("security", "observability"));
        crossDomainRules.put("integration", Set.of("security", "governance"));
        crossDomainRules.put("testing", Set.of("security"));

        // Observability monitors deployment and implementation
        crossDomainRules.put("deployment", Set.of("observability"));
        crossDomainRules.put("implementation", Set.of("observability"));

        // Governance influences architecture and integration
        crossDomainRules.put("system-architecture", Set.of("governance"));
        crossDomainRules.put("integration", Set.of("governance"));

        // Business domain guides implementation
        crossDomainRules.put("implementation", Set.of("business"));
    }

    private void initializeAgentPriorities() {
        // Priorities are already set in registerAgentDependencies
        // Security has highest priority (95), followed by Architecture (100 but core
        // level)
        // Implementation and collaboration agents have high priority
        // Documentation has lowest priority (50)
    }
}