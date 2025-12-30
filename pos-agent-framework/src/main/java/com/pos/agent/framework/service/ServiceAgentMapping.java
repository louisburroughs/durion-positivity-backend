package com.pos.agent.framework.service;

import com.pos.agent.framework.model.AgentType;

import java.util.*;

/**
 * Maps POS services to their primary and suggested agent types.
 * This helps route consultation requests to the most appropriate agents.
 */
public class ServiceAgentMapping {

    private final Map<String, AgentType> primaryAgentMap;
    private final Map<String, List<AgentType>> suggestedAgentsMap;

    public ServiceAgentMapping() {
        this.primaryAgentMap = new HashMap<>();
        this.suggestedAgentsMap = new HashMap<>();
        initializeMappings();
    }

    private void initializeMappings() {
        // Business Domain Services
        addMapping("pos-catalog", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-customer", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.SECURITY));
        addMapping("pos-vehicle-inventory", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-order", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-invoice", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-price", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-accounting", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.SECURITY));
        addMapping("pos-work-order", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-people", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.SECURITY));
        addMapping("pos-location", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-vehicle-fitment", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-vehicle-reference-data", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-vehicle-reference-integration", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.INTEGRATION_GATEWAY));
        addMapping("pos-inquiry", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
        addMapping("pos-shop-manager", AgentType.BUSINESS_DOMAIN,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));

        // Implementation-focused Services
        addMapping("pos-inventory", AgentType.IMPLEMENTATION,
                Arrays.asList(AgentType.BUSINESS_DOMAIN, AgentType.TESTING));
        addMapping("pos-image", AgentType.IMPLEMENTATION,
                Arrays.asList(AgentType.ARCHITECTURE, AgentType.PERFORMANCE));

        // Infrastructure & Platform Services
        addMapping("pos-api-gateway", AgentType.INTEGRATION_GATEWAY,
                Arrays.asList(AgentType.SECURITY, AgentType.RESILIENCE_ENGINEERING));
        addMapping("pos-security-service", AgentType.SECURITY,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.ARCHITECTURE));
        addMapping("pos-service-discovery", AgentType.ARCHITECTURE,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.RESILIENCE_ENGINEERING));

        // Event-Driven Services
        addMapping("pos-events", AgentType.EVENT_DRIVEN_ARCHITECTURE,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.RESILIENCE_ENGINEERING));
        addMapping("pos-event-receiver", AgentType.EVENT_DRIVEN_ARCHITECTURE,
                Arrays.asList(AgentType.RESILIENCE_ENGINEERING, AgentType.IMPLEMENTATION));

        // Framework Services
        addMapping("pos-agent-framework", AgentType.ARCHITECTURE,
                Arrays.asList(AgentType.IMPLEMENTATION, AgentType.TESTING));
    }

    private void addMapping(String serviceName, AgentType primaryAgent, List<AgentType> suggestedAgents) {
        primaryAgentMap.put(serviceName, primaryAgent);
        suggestedAgentsMap.put(serviceName, new ArrayList<>(suggestedAgents));
    }

    /**
     * Gets the primary agent type for a given service.
     *
     * @param serviceName the name of the service
     * @return Optional containing the primary agent type, or empty if no mapping
     *         exists
     */
    public Optional<AgentType> getPrimaryAgent(String serviceName) {
        return Optional.ofNullable(primaryAgentMap.get(serviceName));
    }

    /**
     * Gets the suggested agent types for a given service.
     *
     * @param serviceName the name of the service
     * @return List of suggested agent types, or empty list if no mapping exists
     */
    public List<AgentType> getSuggestedAgents(String serviceName) {
        return suggestedAgentsMap.getOrDefault(serviceName, Collections.emptyList());
    }

    /**
     * Gets all mapped service names.
     *
     * @return Set of all service names with mappings
     */
    public Set<String> getAllMappedServices() {
        return new HashSet<>(primaryAgentMap.keySet());
    }

    /**
     * Checks if a service has a mapping.
     *
     * @param serviceName the name of the service
     * @return true if the service has a mapping, false otherwise
     */
    public boolean hasMappingFor(String serviceName) {
        return primaryAgentMap.containsKey(serviceName);
    }
}
