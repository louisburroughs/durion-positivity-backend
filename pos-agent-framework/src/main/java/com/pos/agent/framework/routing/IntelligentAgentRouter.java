package com.pos.agent.framework.routing;

import com.pos.agent.framework.mapping.ServiceAgentMapping;
import com.pos.agent.framework.mapping.ServiceContextDetector;
import com.pos.agent.framework.mapping.ServiceAgentRoutingRules;
import com.positivity.agent.AgentConsultationRequest;
import com.pos.agent.framework.model.AgentRoutingResult;
import com.pos.agent.framework.mapping.ServiceContextDetector.ServiceAgentSelection;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.Agent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Intelligent agent routing implementation that provides service-aware request routing,
 * context-based agent selection, and fallback mechanisms for unmapped services.
 */
@Component
public class IntelligentAgentRouter {
    
    private static final Logger logger = Logger.getLogger(IntelligentAgentRouter.class.getName());
    
    @Autowired
    private ServiceAgentMapping serviceAgentMapping;
    
    @Autowired
    private ServiceContextDetector serviceContextDetector;
    
    @Autowired
    private ServiceAgentRoutingRules routingRules;
    
    @Autowired
    private AgentRegistry agentRegistry;
    
    /**
     * Routes an agent request to the most appropriate agent based on service context,
     * agent availability, and routing rules.
     */
    public AgentRoutingResult routeRequest(AgentConsultationRequest request) {
        logger.info("Routing request: " + request.domain() + " for context: " + request.query());
        
        // Step 1: Detect service context from request
        ServiceAgentSelection selection = serviceContextDetector.detectServiceContext(request);
        
        // Step 2: Get routing rules for detected service
        ServiceAgentRoutingRules.RoutingRule rule = routingRules.getRoutingRule(selection.getDetectedService());
        
        // Step 3: Try primary agent first
        String primaryAgent = selection.getPrimaryAgent();
        if (primaryAgent != null && isAgentAvailable(primaryAgent)) {
            logger.info("Routing to primary agent: " + primaryAgent);
            return AgentRoutingResult.success(primaryAgent, selection.getDetectedService(), "Primary agent selection");
        }
        
        // Step 4: Try suggested agents from context detection
        for (String suggestedAgent : selection.getSuggestedAgents()) {
            if (isAgentAvailable(suggestedAgent)) {
                logger.info("Routing to suggested agent: " + suggestedAgent);
                return AgentRoutingResult.success(suggestedAgent, selection.getDetectedService(), "Context-based selection");
            }
        }
        
        // Step 5: Try fallback chain from routing rules
        if (rule != null) {
            for (String fallbackAgent : rule.getPreferredAgents()) {
                if (isAgentAvailable(fallbackAgent)) {
                    logger.info("Routing to fallback agent: " + fallbackAgent);
                    return AgentRoutingResult.success(fallbackAgent, selection.getDetectedService(), "Fallback selection");
                }
            }
        }
        
        // Step 6: Try any available agent as last resort
        Optional<String> anyAvailableAgent = findAnyAvailableAgent();
        if (anyAvailableAgent.isPresent()) {
            logger.warning("Using any available agent as last resort: " + anyAvailableAgent.get());
            return AgentRoutingResult.success(anyAvailableAgent.get(), "unknown", "Last resort selection");
        }
        
        // Step 7: No agents available
        logger.severe("No agents available for request: " + request.domain());
        return AgentRoutingResult.failure("No agents available", selection.getDetectedService());
    }
    
    /**
     * Routes a request to a specific service with intelligent agent selection.
     */
    public AgentRoutingResult routeToService(String serviceName, AgentConsultationRequest request) {
        logger.info("Routing request to specific service: " + serviceName);
        
        // Get primary agent for service
        String primaryAgent = serviceAgentMapping.getPrimaryAgent(serviceName);
        if (primaryAgent != null && isAgentAvailable(primaryAgent)) {
            return AgentRoutingResult.success(primaryAgent, serviceName, "Service-specific routing");
        }
        
        // Try supporting agents
        List<String> supportingAgents = serviceAgentMapping.getMappedAgents(serviceName);
        for (String agent : supportingAgents) {
            if (isAgentAvailable(agent)) {
                return AgentRoutingResult.success(agent, serviceName, "Supporting agent selection");
            }
        }
        
        // Use fallback routing
        return routeRequest(request);
    }
    
    /**
     * Gets the best available agent for a specific domain or technology.
     */
    public AgentRoutingResult getBestAgentForDomain(String domain) {
        logger.info("Finding best agent for domain: " + domain);
        
        // Try to detect service context from domain
        AgentConsultationRequest domainRequest = AgentConsultationRequest.create(
            domain,
            "Request for domain: " + domain,
            Map.of("type", "domain-specific")
        );
            
        ServiceAgentSelection selection = serviceContextDetector.detectServiceContext(domainRequest);
        
        // Return primary agent if available
        String primaryAgent = selection.getPrimaryAgent();
        if (primaryAgent != null && isAgentAvailable(primaryAgent)) {
            return AgentRoutingResult.success(primaryAgent, domain, "Domain-specific selection");
        }
        
        // Try suggested agents
        for (String suggestedAgent : selection.getSuggestedAgents()) {
            if (isAgentAvailable(suggestedAgent)) {
                return AgentRoutingResult.success(suggestedAgent, domain, "Domain suggestion");
            }
        }
        
        return AgentRoutingResult.failure("No suitable agent found for domain: " + domain, domain);
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
     * Finds any available agent as a last resort.
     */
    private Optional<String> findAnyAvailableAgent() {
        try {
            List<Agent> availableAgents = agentRegistry.getAvailableAgents();
            return availableAgents.isEmpty() ? Optional.empty() : Optional.of(availableAgents.get(0).getId());
        } catch (Exception e) {
            logger.severe("Error finding available agents: " + e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Gets routing statistics for monitoring and optimization.
     */
    public RoutingStatistics getRoutingStatistics() {
        return RoutingStatistics.builder()
            .totalRequests(0) // TODO: Implement request counting
            .successfulRoutes(0) // TODO: Implement success tracking
            .fallbackRoutes(0) // TODO: Implement fallback tracking
            .failedRoutes(0) // TODO: Implement failure tracking
            .build();
    }
    
    /**
     * Statistics for routing performance monitoring.
     */
    public static class RoutingStatistics {
        private final int totalRequests;
        private final int successfulRoutes;
        private final int fallbackRoutes;
        private final int failedRoutes;
        
        private RoutingStatistics(int totalRequests, int successfulRoutes, int fallbackRoutes, int failedRoutes) {
            this.totalRequests = totalRequests;
            this.successfulRoutes = successfulRoutes;
            this.fallbackRoutes = fallbackRoutes;
            this.failedRoutes = failedRoutes;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public int getTotalRequests() { return totalRequests; }
        public int getSuccessfulRoutes() { return successfulRoutes; }
        public int getFallbackRoutes() { return fallbackRoutes; }
        public int getFailedRoutes() { return failedRoutes; }
        public double getSuccessRate() { 
            return totalRequests > 0 ? (double) successfulRoutes / totalRequests : 0.0; 
        }
        
        public static class Builder {
            private int totalRequests;
            private int successfulRoutes;
            private int fallbackRoutes;
            private int failedRoutes;
            
            public Builder totalRequests(int totalRequests) { this.totalRequests = totalRequests; return this; }
            public Builder successfulRoutes(int successfulRoutes) { this.successfulRoutes = successfulRoutes; return this; }
            public Builder fallbackRoutes(int fallbackRoutes) { this.fallbackRoutes = fallbackRoutes; return this; }
            public Builder failedRoutes(int failedRoutes) { this.failedRoutes = failedRoutes; return this; }
            
            public RoutingStatistics build() {
                return new RoutingStatistics(totalRequests, successfulRoutes, fallbackRoutes, failedRoutes);
            }
        }
    }
}
