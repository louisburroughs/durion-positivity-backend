package com.pos.agent.framework.mapping;

import java.util.*;

/**
 * Service-Agent Mapping Configuration
 * Maps each microservice to appropriate agent types based on domain and functionality
 */
public class ServiceAgentMapping {
    
    private static final Map<String, List<String>> SERVICE_AGENT_MAPPINGS = new HashMap<>();
    private static final Map<String, String> SERVICE_PRIMARY_AGENTS = new HashMap<>();
    
    static {
        initializeServiceMappings();
    }
    
    private static void initializeServiceMappings() {
        // API Gateway Service
        mapService("pos-api-gateway", "api-gateway-specialist", 
            Arrays.asList("api-gateway-specialist", "security-specialist", "observability-engineer"));
        
        // Security Service
        mapService("pos-security-service", "security-specialist",
            Arrays.asList("security-specialist", "configuration-management", "observability-engineer"));
        
        // Core Business Services
        mapService("pos-catalog", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "database-per-service-specialist", "business-domain"));
        
        mapService("pos-customer", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "database-per-service-specialist", "business-domain"));
        
        mapService("pos-inventory", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "database-per-service-specialist", "business-domain"));
        
        mapService("pos-vehicle-inventory", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"));
        
        mapService("pos-order", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "event-driven-specialist", "business-domain"));
        
        mapService("pos-invoice", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "business-domain", "database-per-service-specialist"));
        
        mapService("pos-price", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"));
        
        mapService("pos-accounting", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"));
        
        mapService("pos-work-order", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "event-driven-specialist"));
        
        mapService("pos-people", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "security-specialist", "database-per-service-specialist"));
        
        mapService("pos-location", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "database-per-service-specialist", "business-domain"));
        
        // Event Services
        mapService("pos-events", "event-driven-specialist",
            Arrays.asList("event-driven-specialist", "spring-boot-developer", "observability-engineer"));
        
        mapService("pos-event-receiver", "event-driven-specialist",
            Arrays.asList("event-driven-specialist", "spring-boot-developer", "resilience-engineering"));
        
        // Media and Content Services
        mapService("pos-image", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "database-per-service-specialist", "containerization-specialist"));
        
        // Vehicle-Specific Services
        mapService("pos-vehicle-fitment", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"));
        
        mapService("pos-vehicle-reference-data", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "database-per-service-specialist"));
        
        mapService("pos-vehicle-reference-integration", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "api-gateway-specialist"));
        
        // Support Services
        mapService("pos-inquiry", "spring-boot-developer",
            Arrays.asList("spring-boot-developer", "business-domain", "database-per-service-specialist"));
        
        mapService("pos-shop-manager", "business-domain",
            Arrays.asList("business-domain", "spring-boot-developer", "event-driven-specialist"));
        
        // Infrastructure Services
        mapService("pos-service-discovery", "microservices-architect",
            Arrays.asList("microservices-architect", "containerization-specialist", "observability-engineer"));
        
        mapService("pos-config-server", "configuration-management",
            Arrays.asList("configuration-management", "security-specialist", "microservices-architect"));
    }
    
    private static void mapService(String serviceName, String primaryAgent, List<String> supportingAgents) {
        SERVICE_PRIMARY_AGENTS.put(serviceName, primaryAgent);
        SERVICE_AGENT_MAPPINGS.put(serviceName, new ArrayList<>(supportingAgents));
    }
    
    /**
     * Get the primary agent for a service
     */
    public static String getPrimaryAgent(String serviceName) {
        return SERVICE_PRIMARY_AGENTS.get(serviceName);
    }
    
    /**
     * Get all mapped agents for a service (including primary)
     */
    public static List<String> getMappedAgents(String serviceName) {
        return SERVICE_AGENT_MAPPINGS.getOrDefault(serviceName, Collections.emptyList());
    }
    
    /**
     * Get all mapped services
     */
    public static Set<String> getAllServices() {
        return SERVICE_AGENT_MAPPINGS.keySet();
    }
    
    /**
     * Check if a service is mapped
     */
    public static boolean isServiceMapped(String serviceName) {
        return SERVICE_AGENT_MAPPINGS.containsKey(serviceName);
    }
    
    /**
     * Get services mapped to a specific agent
     */
    public static List<String> getServicesForAgent(String agentType) {
        List<String> services = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SERVICE_AGENT_MAPPINGS.entrySet()) {
            if (entry.getValue().contains(agentType)) {
                services.add(entry.getKey());
            }
        }
        return services;
    }
}
