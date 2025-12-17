package com.pos.agent.core;

/**
 * Enumeration of all available agent types in the system.
 * Each agent type represents a specialized domain of expertise.
 */
public enum AgentType {
    ARCHITECTURE("Architecture Agent", "System design and architectural guidance"),
    IMPLEMENTATION("Implementation Agent", "Code implementation and development"),
    DEPLOYMENT("Deployment Agent", "Deployment and infrastructure management"),
    TESTING("Testing Agent", "Testing strategy and implementation"),
    SECURITY("Security Agent", "Security best practices and compliance"),
    OBSERVABILITY("Observability Agent", "Monitoring and observability setup"),
    DOCUMENTATION("Documentation Agent", "Documentation generation and maintenance"),
    BUSINESS_DOMAIN("Business Domain Agent", "Business logic and domain validation"),
    INTEGRATION_GATEWAY("Integration Gateway Agent", "API gateway and integration patterns"),
    PAIR_PROGRAMMING_NAVIGATOR("Pair Programming Navigator Agent", "Code review and pair programming"),
    EVENT_DRIVEN_ARCHITECTURE("Event-Driven Architecture Agent", "Event-driven patterns and messaging"),
    CICD_PIPELINE("CI/CD Pipeline Agent", "Continuous integration and deployment"),
    CONFIGURATION_MANAGEMENT("Configuration Management Agent", "Configuration and secrets management"),
    RESILIENCE_ENGINEERING("Resilience Engineering Agent", "Reliability and resilience patterns");

    private final String displayName;
    private final String description;

    AgentType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}