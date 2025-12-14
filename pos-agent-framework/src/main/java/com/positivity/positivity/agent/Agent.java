package com.positivity.positivity.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface for all agents in the positivity POS system.
 * Defines the contract for specialized AI agents that provide domain-specific expertise.
 */
public interface Agent {
    
    /**
     * Unique identifier for the agent
     */
    String getId();
    
    /**
     * Human-readable name for the agent
     */
    String getName();
    
    /**
     * Primary domain of expertise
     */
    String getDomain();
    
    /**
     * Specific capabilities and knowledge areas
     */
    Set<String> getCapabilities();
    
    /**
     * Other agents this agent depends on or collaborates with
     */
    Set<String> getDependencies();
    
    /**
     * Performance specifications for this agent
     */
    AgentPerformanceSpec getPerformanceSpec();
    
    /**
     * Provide guidance for a consultation request
     * @param request The consultation request
     * @return Future containing the guidance response
     */
    CompletableFuture<AgentGuidanceResponse> provideGuidance(AgentConsultationRequest request);
    
    /**
     * Validate that this agent can handle the given request
     * @param request The consultation request
     * @return true if this agent can provide guidance for the request
     */
    boolean canHandle(AgentConsultationRequest request);
    
    /**
     * Get the current health status of the agent
     */
    AgentHealthStatus getHealthStatus();
    
    /**
     * Check if the agent is available for consultation
     */
    boolean isAvailable();
    
    /**
     * Get performance metrics for this agent
     */
    AgentMetrics getMetrics();
}