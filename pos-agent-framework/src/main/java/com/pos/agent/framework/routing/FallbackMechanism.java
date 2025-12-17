package com.pos.agent.framework.routing;

import com.positivity.agent.AgentConsultationRequest;
import com.pos.agent.framework.model.AgentRoutingResult;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.Agent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;

/**
 * Fallback mechanism for handling unmapped services and agent failures.
 * Provides multiple fallback strategies to ensure requests are always handled.
 */
@Component
public class FallbackMechanism {
    
    private static final Logger logger = Logger.getLogger(FallbackMechanism.class.getName());
    
    @Autowired
    private AgentRegistry agentRegistry;
    
    @Autowired
    private ContextBasedAgentSelector contextBasedSelector;
    
    // Default fallback chains for different scenarios
    private static final Map<String, List<String>> DEFAULT_FALLBACK_CHAINS = Map.ofEntries(
        Map.entry("spring-boot", Arrays.asList("spring-boot-developer", "microservices-architect", "spring-boot-pair-navigator")),
        Map.entry("security", Arrays.asList("security-specialist", "spring-boot-developer", "microservices-architect")),
        Map.entry("database", Arrays.asList("database-per-service-specialist", "spring-boot-developer", "microservices-architect")),
        Map.entry("deployment", Arrays.asList("containerization-specialist", "microservices-architect", "spring-boot-developer")),
        Map.entry("testing", Arrays.asList("microservices-testing-specialist", "spring-boot-developer", "spring-boot-pair-navigator")),
        Map.entry("monitoring", Arrays.asList("observability-engineer", "microservices-architect", "spring-boot-developer")),
        Map.entry("events", Arrays.asList("event-driven-specialist", "microservices-architect", "spring-boot-developer")),
        Map.entry("gateway", Arrays.asList("api-gateway-specialist", "microservices-architect", "spring-boot-developer")),
        Map.entry("business", Arrays.asList("business-domain-specialist", "spring-boot-developer", "microservices-architect"))
    );
    
    // Universal fallback agents (always available as last resort)
    private static final List<String> UNIVERSAL_FALLBACK_AGENTS = Arrays.asList(
        "spring-boot-developer",
        "microservices-architect",
        "spring-boot-pair-navigator"
    );
    
    /**
     * Handles fallback routing when primary routing fails.
     */
    public AgentRoutingResult handleFallback(AgentConsultationRequest request, String reason) {
        logger.info("Handling fallback routing for request: " + request.domain() + ", reason: " + reason);
        
        // Strategy 1: Context-based fallback
        AgentRoutingResult contextResult = tryContextBasedFallback(request);
        if (contextResult.isSuccess()) {
            return contextResult;
        }
        
        // Strategy 2: Domain-based fallback chains
        AgentRoutingResult domainResult = tryDomainBasedFallback(request);
        if (domainResult.isSuccess()) {
            return domainResult;
        }
        
        // Strategy 3: Universal fallback agents
        AgentRoutingResult universalResult = tryUniversalFallback(request);
        if (universalResult.isSuccess()) {
            return universalResult;
        }
        
        // Strategy 4: Any available agent
        AgentRoutingResult anyAgentResult = tryAnyAvailableAgent(request);
        if (anyAgentResult.isSuccess()) {
            return anyAgentResult;
        }
        
        // All fallback strategies failed
        return AgentRoutingResult.failure("All fallback strategies exhausted", "unknown");
    }
    
    /**
     * Tries context-based fallback using intelligent context analysis.
     */
    private AgentRoutingResult tryContextBasedFallback(AgentConsultationRequest request) {
        try {
            List<String> suggestedAgents = contextBasedSelector.getSuggestedAgents(request, 3);
            
            for (String agent : suggestedAgents) {
                if (isAgentAvailable(agent)) {
                    logger.info("Context-based fallback selected: " + agent);
                    return AgentRoutingResult.success(agent, "context-fallback", "Context-based fallback selection");
                }
            }
        } catch (Exception e) {
            logger.warning("Context-based fallback failed: " + e.getMessage());
        }
        
        return AgentRoutingResult.failure("Context-based fallback failed", "unknown");
    }
    
    /**
     * Tries domain-based fallback using predefined fallback chains.
     */
    private AgentRoutingResult tryDomainBasedFallback(AgentConsultationRequest request) {
        String requestText = (request.query() + " " + request.domain()).toLowerCase();
        
        // Find matching domain
        for (Map.Entry<String, List<String>> entry : DEFAULT_FALLBACK_CHAINS.entrySet()) {
            String domain = entry.getKey();
            List<String> fallbackChain = entry.getValue();
            
            if (requestText.contains(domain)) {
                for (String agent : fallbackChain) {
                    if (isAgentAvailable(agent)) {
                        logger.info("Domain-based fallback selected: " + agent + " for domain: " + domain);
                        return AgentRoutingResult.success(agent, "domain-fallback", 
                                                        "Domain-based fallback for " + domain);
                    }
                }
            }
        }
        
        return AgentRoutingResult.failure("Domain-based fallback failed", "unknown");
    }
    
    /**
     * Tries universal fallback agents that can handle most requests.
     */
    private AgentRoutingResult tryUniversalFallback(AgentConsultationRequest request) {
        for (String agent : UNIVERSAL_FALLBACK_AGENTS) {
            if (isAgentAvailable(agent)) {
                logger.info("Universal fallback selected: " + agent);
                return AgentRoutingResult.success(agent, "universal-fallback", "Universal fallback selection");
            }
        }
        
        return AgentRoutingResult.failure("Universal fallback failed", "unknown");
    }
    
    /**
     * Tries any available agent as absolute last resort.
     */
    private AgentRoutingResult tryAnyAvailableAgent(AgentConsultationRequest request) {
        try {
            List<Agent> availableAgents = agentRegistry.getAvailableAgents();
            if (!availableAgents.isEmpty()) {
                String selectedAgent = availableAgents.get(0).getId();
                logger.warning("Last resort fallback selected: " + selectedAgent);
                return AgentRoutingResult.success(selectedAgent, "last-resort", "Last resort - any available agent");
            }
        } catch (Exception e) {
            logger.severe("Failed to get available agents: " + e.getMessage());
        }
        
        return AgentRoutingResult.failure("No agents available", "unknown");
    }
    
    /**
     * Handles agent failure and provides alternative routing.
     */
    public AgentRoutingResult handleAgentFailure(String failedAgent, AgentConsultationRequest request) {
        logger.warning("Handling agent failure for: " + failedAgent);
        
        // Find fallback chain for the failed agent
        List<String> fallbackChain = getFallbackChainForAgent(failedAgent);
        
        for (String fallbackAgent : fallbackChain) {
            if (!fallbackAgent.equals(failedAgent) && isAgentAvailable(fallbackAgent)) {
                logger.info("Agent failure fallback selected: " + fallbackAgent);
                return AgentRoutingResult.success(fallbackAgent, "agent-failure-fallback", 
                                                "Fallback due to " + failedAgent + " failure");
            }
        }
        
        // Use general fallback mechanism
        return handleFallback(request, "Agent failure: " + failedAgent);
    }
    
    /**
     * Gets fallback chain for a specific agent type.
     */
    private List<String> getFallbackChainForAgent(String agentType) {
        // Agent-specific fallback chains
        Map<String, List<String>> agentFallbacks = Map.ofEntries(
            Map.entry("spring-boot-developer", Arrays.asList("microservices-architect", "spring-boot-pair-navigator")),
            Map.entry("security-specialist", Arrays.asList("spring-boot-developer", "microservices-architect")),
            Map.entry("api-gateway-specialist", Arrays.asList("microservices-architect", "spring-boot-developer")),
            Map.entry("database-per-service-specialist", Arrays.asList("spring-boot-developer", "microservices-architect")),
            Map.entry("containerization-specialist", Arrays.asList("microservices-architect", "spring-boot-developer")),
            Map.entry("observability-engineer", Arrays.asList("microservices-architect", "spring-boot-developer")),
            Map.entry("microservices-testing-specialist", Arrays.asList("spring-boot-developer", "spring-boot-pair-navigator")),
            Map.entry("event-driven-specialist", Arrays.asList("microservices-architect", "spring-boot-developer")),
            Map.entry("business-domain-specialist", Arrays.asList("spring-boot-developer", "microservices-architect"))
        );
        
        return agentFallbacks.getOrDefault(agentType, UNIVERSAL_FALLBACK_AGENTS);
    }
    
    /**
     * Checks if an agent is available and healthy.
     */
    private boolean isAgentAvailable(String agentType) {
        try {
            return agentRegistry.getAvailableAgents().stream()
                .anyMatch(agent -> agent.getId().equals(agentType) || 
                         agent.getClass().getSimpleName().toLowerCase().contains(agentType.toLowerCase()));
        } catch (Exception e) {
            logger.warning("Error checking agent availability for " + agentType + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets fallback statistics for monitoring.
     */
    public FallbackStatistics getFallbackStatistics() {
        return new FallbackStatistics(
            0, // TODO: Implement fallback request counting
            0, // TODO: Implement context-based fallback counting
            0, // TODO: Implement domain-based fallback counting
            0, // TODO: Implement universal fallback counting
            0  // TODO: Implement last resort counting
        );
    }
    
    /**
     * Statistics for fallback mechanism monitoring.
     */
    public static class FallbackStatistics {
        private final int totalFallbackRequests;
        private final int contextBasedFallbacks;
        private final int domainBasedFallbacks;
        private final int universalFallbacks;
        private final int lastResortFallbacks;
        
        public FallbackStatistics(int totalFallbackRequests, int contextBasedFallbacks, 
                                int domainBasedFallbacks, int universalFallbacks, int lastResortFallbacks) {
            this.totalFallbackRequests = totalFallbackRequests;
            this.contextBasedFallbacks = contextBasedFallbacks;
            this.domainBasedFallbacks = domainBasedFallbacks;
            this.universalFallbacks = universalFallbacks;
            this.lastResortFallbacks = lastResortFallbacks;
        }
        
        public int getTotalFallbackRequests() { return totalFallbackRequests; }
        public int getContextBasedFallbacks() { return contextBasedFallbacks; }
        public int getDomainBasedFallbacks() { return domainBasedFallbacks; }
        public int getUniversalFallbacks() { return universalFallbacks; }
        public int getLastResortFallbacks() { return lastResortFallbacks; }
    }
}
