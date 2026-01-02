package com.pos.agent.core;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.pos.agent.framework.model.AgentType;

public interface AgentRegistry {
    void registerAgent(Agent agent);
    boolean unregisterAgent(Agent agent);
    void clearAgents();
    List<Agent> listAgents();
    CompletableFuture<AgentResponse> consultBestAgent(AgentRequest request);
    RegistryHealthStatus getHealthStatus();
    List<Agent> getAgentsWithCapabilities(Set<String> capabilities);
    List<Agent> getAgentsForTechnicalDomain(String technicalDomain);
    List<Agent> getAgentsForAgentType(AgentType searchAgentType);
}
