package com.pos.agent.framework.model;

/**
 * Represents the different types of agents available in the framework.
 * Each agent type specializes in specific aspects of software development.
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
     * Integration Gateway agent - focuses on API gateways and integration patterns
     */
    INTEGRATION_GATEWAY,

    /**
     * Event Driven Architecture agent - focuses on event-driven design patterns
     */
    EVENT_DRIVEN_ARCHITECTURE,

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
    DOCUMENTATION
}
