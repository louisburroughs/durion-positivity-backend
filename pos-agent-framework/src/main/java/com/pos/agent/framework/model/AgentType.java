package com.pos.agent.framework.model;

/**
 * Represents the different types of agents available in the framework.
 * Each agent type specializes in specific aspects of software development.
 * 
 * Source of Truth: This enum must be synchronized with agent configurations in
 * application.yml
 * All agents defined in application.yml must have a corresponding enum value
 * here.
 */
public enum AgentType {
    /**
     * Business Domain agent - focuses on business logic and domain modeling
     */
    BUSINESS_DOMAIN,

    /**
     * Implementation agent - focuses on code implementation details
     */
    IMPLEMENTATION,

    /**
     * Architecture agent - focuses on system architecture and design patterns
     */
    ARCHITECTURE,

    /**
     * Security agent - focuses on security best practices and vulnerabilities
     */
    SECURITY,

    /**
     * Testing agent - focuses on test strategies and quality assurance
     */
    TESTING,

    /**
     * Observability agent - focuses on monitoring, logging, and observability
     * patterns
     */
    OBSERVABILITY,

    /**
     * Deployment agent - focuses on deployment strategies and release management
     */
    DEPLOYMENT,

    /**
     * Integration Gateway agent - focuses on API gateways and integration patterns
     */
    INTEGRATION_GATEWAY,

    /**
     * Event Driven Architecture agent - focuses on event-driven design patterns
     */
    EVENT_DRIVEN_ARCHITECTURE,

    /**
     * Pair Programming agent - focuses on collaborative code review and pair
     * programming practices
     */
    PAIR_PROGRAMMING,

    /**
     * Resilience Engineering agent - focuses on fault tolerance and reliability
     */
    RESILIENCE_ENGINEERING,

    /**
     * Configuration Management agent - focuses on configuration and deployment
     */
    CONFIGURATION_MANAGEMENT,

    /**
     * CI/CD Pipeline agent - focuses on continuous integration and deployment
     */
    CICD_PIPELINE,

    /**
     * Performance agent - focuses on performance optimization
     */
    PERFORMANCE,

    /**
     * Documentation agent - focuses on documentation quality
     */
    DOCUMENTATION;

    /**
     * Checks if this AgentType matches the given string, case-insensitive.
     * 
     * @param value the string to compare against
     * @return {@code true} if this AgentType's name matches the value (ignoring
     *         case), {@code false} otherwise
     */
    public boolean equalsIgnoreCase(String value) {
        if (value == null) {
            return false;
        }
        return this.name().equalsIgnoreCase(value);
    }

    /**
     * Finds an AgentType from a string value, case-insensitive.
     * Handles both underscore and hyphen separators.
     * 
     * @param value the string to search for
     * @return the matching AgentType, or {@code null} if no match found
     */
    public static AgentType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        for (AgentType type : AgentType.values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }

}
