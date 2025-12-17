package com.pos.agent.framework.mapping;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Service-Specific Agent Preferences and Routing Rules
 * Defines routing preferences, fallback strategies, and service-specific agent configurations
 */
@Component
public class ServiceAgentRoutingRules {
    
    private static final Map<String, RoutingRule> SERVICE_ROUTING_RULES = new HashMap<>();
    private static final Map<String, List<String>> AGENT_FALLBACK_CHAINS = new HashMap<>();
    
    static {
        initializeRoutingRules();
        initializeFallbackChains();
    }
    
    private static void initializeRoutingRules() {
        // API Gateway - High priority for gateway specialist
        SERVICE_ROUTING_RULES.put("pos-api-gateway", new RoutingRule(
            "api-gateway-specialist", 
            Arrays.asList("api-gateway-specialist", "security-specialist", "observability-engineer"),
            RoutingPriority.HIGH,
            true // requires specialized knowledge
        ));
        
        // Security Service - Critical security focus
        SERVICE_ROUTING_RULES.put("pos-security-service", new RoutingRule(
            "security-specialist",
            Arrays.asList("security-specialist", "configuration-management", "observability-engineer"),
            RoutingPriority.CRITICAL,
            true
        ));
        
        // Event Services - Event-driven specialist priority
        SERVICE_ROUTING_RULES.put("pos-events", new RoutingRule(
            "event-driven-specialist",
            Arrays.asList("event-driven-specialist", "spring-boot-developer", "observability-engineer"),
            RoutingPriority.HIGH,
            true
        ));
        
        SERVICE_ROUTING_RULES.put("pos-event-receiver", new RoutingRule(
            "event-driven-specialist",
            Arrays.asList("event-driven-specialist", "resilience-engineering", "spring-boot-developer"),
            RoutingPriority.HIGH,
            true
        ));
        
        // Vehicle-specific services - Business domain priority
        SERVICE_ROUTING_RULES.put("pos-vehicle-inventory", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"),
            RoutingPriority.HIGH,
            false
        ));
        
        SERVICE_ROUTING_RULES.put("pos-vehicle-fitment", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"),
            RoutingPriority.HIGH,
            false
        ));
        
        SERVICE_ROUTING_RULES.put("pos-vehicle-reference-data", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "api-gateway-specialist"),
            RoutingPriority.MEDIUM,
            false
        ));
        
        // Core business services - Standard Spring Boot development
        Arrays.asList("pos-catalog", "pos-customer", "pos-inventory", "pos-order", 
                     "pos-invoice", "pos-people", "pos-location", "pos-image", "pos-inquiry")
            .forEach(service -> SERVICE_ROUTING_RULES.put(service, new RoutingRule(
                "spring-boot-developer",
                Arrays.asList("spring-boot-developer", "database-per-service-specialist", "business-domain"),
                RoutingPriority.MEDIUM,
                false
            )));
        
        // Specialized business services
        SERVICE_ROUTING_RULES.put("pos-price", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"),
            RoutingPriority.HIGH,
            false
        ));
        
        SERVICE_ROUTING_RULES.put("pos-accounting", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"),
            RoutingPriority.HIGH,
            false
        ));
        
        SERVICE_ROUTING_RULES.put("pos-work-order", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "event-driven-specialist"),
            RoutingPriority.HIGH,
            false
        ));
        
        SERVICE_ROUTING_RULES.put("pos-shop-manager", new RoutingRule(
            "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "event-driven-specialist"),
            RoutingPriority.HIGH,
            false
        ));
        
        // Infrastructure services
        SERVICE_ROUTING_RULES.put("pos-service-discovery", new RoutingRule(
            "microservices-architect",
            Arrays.asList("microservices-architect", "containerization-specialist", "observability-engineer"),
            RoutingPriority.HIGH,
            true
        ));
        
        SERVICE_ROUTING_RULES.put("pos-config-server", new RoutingRule(
            "configuration-management",
            Arrays.asList("configuration-management", "security-specialist", "microservices-architect"),
            RoutingPriority.HIGH,
            true
        ));
    }
    
    private static void initializeFallbackChains() {
        // Primary agent fallback chains
        AGENT_FALLBACK_CHAINS.put("api-gateway-specialist", 
            Arrays.asList("microservices-architect", "spring-boot-developer"));
        
        AGENT_FALLBACK_CHAINS.put("security-specialist", 
            Arrays.asList("microservices-architect", "spring-boot-developer"));
        
        AGENT_FALLBACK_CHAINS.put("event-driven-specialist", 
            Arrays.asList("spring-boot-developer", "microservices-architect"));
        
        AGENT_FALLBACK_CHAINS.put("business-domain", 
            Arrays.asList("spring-boot-developer", "microservices-architect"));
        
        AGENT_FALLBACK_CHAINS.put("database-per-service-specialist", 
            Arrays.asList("spring-boot-developer", "microservices-architect"));
        
        AGENT_FALLBACK_CHAINS.put("microservices-testing-specialist", 
            Arrays.asList("spring-boot-developer", "microservices-architect"));
        
        AGENT_FALLBACK_CHAINS.put("containerization-specialist", 
            Arrays.asList("microservices-architect", "spring-boot-developer"));
        
        AGENT_FALLBACK_CHAINS.put("observability-engineer", 
            Arrays.asList("microservices-architect", "spring-boot-developer"));
        
        AGENT_FALLBACK_CHAINS.put("microservices-architect", 
            Arrays.asList("spring-boot-developer"));
        
        AGENT_FALLBACK_CHAINS.put("spring-boot-developer", 
            Arrays.asList("microservices-architect"));
        
        // New specialized agents
        AGENT_FALLBACK_CHAINS.put("cicd-pipeline", 
            Arrays.asList("containerization-specialist", "microservices-architect"));
        
        AGENT_FALLBACK_CHAINS.put("configuration-management", 
            Arrays.asList("security-specialist", "microservices-architect"));
        
        AGENT_FALLBACK_CHAINS.put("resilience-engineering", 
            Arrays.asList("microservices-architect", "spring-boot-developer"));
    }
    
    /**
     * Get routing rule for a service
     */
    public RoutingRule getRoutingRule(String serviceName) {
        return SERVICE_ROUTING_RULES.get(serviceName);
    }
    
    /**
     * Get fallback agents for a primary agent
     */
    public List<String> getFallbackAgents(String primaryAgent) {
        return AGENT_FALLBACK_CHAINS.getOrDefault(primaryAgent, 
            Arrays.asList("spring-boot-developer", "microservices-architect"));
    }
    
    /**
     * Determine if service requires specialized agent
     */
    public boolean requiresSpecializedAgent(String serviceName) {
        RoutingRule rule = SERVICE_ROUTING_RULES.get(serviceName);
        return rule != null && rule.requiresSpecialization;
    }
    
    /**
     * Get routing priority for service
     */
    public RoutingPriority getRoutingPriority(String serviceName) {
        RoutingRule rule = SERVICE_ROUTING_RULES.get(serviceName);
        return rule != null ? rule.priority : RoutingPriority.MEDIUM;
    }
    
    /**
     * Get all services with specific routing priority
     */
    public List<String> getServicesByPriority(RoutingPriority priority) {
        List<String> services = new ArrayList<>();
        for (Map.Entry<String, RoutingRule> entry : SERVICE_ROUTING_RULES.entrySet()) {
            if (entry.getValue().priority == priority) {
                services.add(entry.getKey());
            }
        }
        return services;
    }
    
    /**
     * Routing Rule Definition
     */
    public static class RoutingRule {
        private final String primaryAgent;
        private final List<String> preferredAgents;
        private final RoutingPriority priority;
        private final boolean requiresSpecialization;
        
        public RoutingRule(String primaryAgent, List<String> preferredAgents, 
                          RoutingPriority priority, boolean requiresSpecialization) {
            this.primaryAgent = primaryAgent;
            this.preferredAgents = new ArrayList<>(preferredAgents);
            this.priority = priority;
            this.requiresSpecialization = requiresSpecialization;
        }
        
        public String getPrimaryAgent() { return primaryAgent; }
        public List<String> getPreferredAgents() { return preferredAgents; }
        public RoutingPriority getPriority() { return priority; }
        public boolean requiresSpecialization() { return requiresSpecialization; }
    }
    
    /**
     * Routing Priority Levels
     */
    public enum RoutingPriority {
        CRITICAL(1),    // Security, authentication services
        HIGH(2),        // Gateway, events, specialized business services
        MEDIUM(3),      // Standard business services
        LOW(4);         // General utility services
        
        private final int level;
        
        RoutingPriority(int level) {
            this.level = level;
        }
        
        public int getLevel() { return level; }
        
        public boolean isHigherThan(RoutingPriority other) {
            return this.level < other.level;
        }
    }
}
