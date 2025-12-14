package com.positivity.agent.registry;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Registry for managing and discovering agents in the system
 */
public interface AgentRegistry {
    
    /**
     * Register an agent in the registry
     * @param agent The agent to register
     */
    void registerAgent(Agent agent);
    
    /**
     * Unregister an agent from the registry
     * @param agentId The ID of the agent to unregister
     */
    void unregisterAgent(String agentId);
    
    /**
     * Get an agent by ID
     * @param agentId The agent ID
     * @return Optional containing the agent if found
     */
    Optional<Agent> getAgent(String agentId);
    
    /**
     * Get all registered agents
     * @return List of all registered agents
     */
    List<Agent> getAllAgents();
    
    /**
     * Find agents capable of handling a specific domain
     * @param domain The domain to search for
     * @return List of agents that can handle the domain
     */
    List<Agent> getAgentsForDomain(String domain);
    
    /**
     * Find agents with specific capabilities
     * @param capabilities The capabilities to search for
     * @return List of agents that have the specified capabilities
     */
    List<Agent> getAgentsWithCapabilities(Set<String> capabilities);
    
    /**
     * Find the best agent for a consultation request
     * @param request The consultation request
     * @return Optional containing the best agent for the request
     */
    Optional<Agent> findBestAgent(AgentConsultationRequest request);
    
    /**
     * Get available agents (healthy and not at capacity)
     * @return List of available agents
     */
    List<Agent> getAvailableAgents();
    
    /**
     * Get backup agents for a specific agent
     * @param agentId The primary agent ID
     * @return List of backup agents
     */
    List<Agent> getBackupAgents(String agentId);
    
    /**
     * Consult the registry to get guidance from the best available agent
     * @param request The consultation request
     * @return Future containing the guidance response
     */
    CompletableFuture<AgentGuidanceResponse> consultBestAgent(AgentConsultationRequest request);
    
    /**
     * Get registry health status
     * @return Registry health information
     */
    RegistryHealthStatus getHealthStatus();
}