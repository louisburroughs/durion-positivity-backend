package com.pos.agent.discovery;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.framework.service.ServiceAgentMapping;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Discovers agents based on domain and service mapping.
 * Uses ServiceAgentMapping as primary lookup mechanism,
 * with capability-based fallback for unmapped domains.
 * 
 * Priority: HIGH - Domain-based routing is most reliable.
 */
public class DomainBasedDiscoveryStrategy implements AgentDiscoveryStrategy {

    private final ServiceAgentMapping serviceMapping;
    private static final int PRIORITY = 100;

    public DomainBasedDiscoveryStrategy(ServiceAgentMapping serviceMapping) {
        this.serviceMapping = Objects.requireNonNull(serviceMapping);
    }

    @Override
    public boolean canHandle(AgentRequest request) {
        AgentDiscoveryContext context = AgentDiscoveryContext.fromRequest(request);
        return !context.getDomain().isEmpty();
    }

    @Override
    public CompletableFuture<Optional<Agent>> discoverBestAgent(
            AgentRequest request,
            List<Agent> availableAgents) {

        return CompletableFuture.supplyAsync(() -> {
            AgentDiscoveryContext context = AgentDiscoveryContext.fromRequest(request);
            String domain = context.getDomain();

            // Step 1: Try direct service-to-agent mapping
            Optional<AgentType> primaryAgentType = serviceMapping.getPrimaryAgent(domain);
            if (primaryAgentType.isPresent()) {
                Optional<Agent> agent = findAgentByType(availableAgents, primaryAgentType.get());
                if (agent.isPresent()) {
                    return agent;
                }
            }

            // Step 2: Try suggested agents for the domain
            List<AgentType> suggestedTypes = serviceMapping.getSuggestedAgents(domain);
            for (AgentType suggestedType : suggestedTypes) {
                Optional<Agent> agent = findAgentByType(availableAgents, suggestedType);
                if (agent.isPresent()) {
                    return agent;
                }
            }

            // Step 3: Fallback to capability-based matching
            return findAgentByCapability(availableAgents, domain);
        });
    }

    private Optional<Agent> findAgentByType(List<Agent> agents, AgentType type) {
        return agents.stream()
                .filter(agent -> type.equals(agent.getTechnicalDomain()))
                .findFirst();
    }

    private Optional<Agent> findAgentByCapability(List<Agent> agents, String domain) {
        return agents.stream()
                .filter(agent -> {
                    List<String> capabilities = agent.getCapabilities();
                    return capabilities != null &&
                            capabilities.contains(domain);
                })
                .findFirst();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }
}
