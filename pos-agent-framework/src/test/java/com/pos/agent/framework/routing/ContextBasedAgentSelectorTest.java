package com.pos.agent.framework.routing;

import com.pos.agent.framework.model.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ContextBasedAgentSelectorTest {

    private ContextBasedAgentSelector selector;

    @BeforeEach
    void setUp() {
        selector = new ContextBasedAgentSelector();
    }

    @Test
    void testSelectAgent_SpringBootContext() {
        // Given
        String context = "Spring Boot microservice implementation with REST API";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testSelectAgent_SecurityContext() {
        // Given
        String context = "JWT authentication and Spring Security configuration";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.SECURITY, result.get());
    }

    @Test
    void testSelectAgent_ArchitectureContext() {
        // Given
        String context = "Microservices architecture design and service boundaries";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testSelectAgent_TestingContext() {
        // Given
        String context = "Unit testing with JUnit and TestContainers integration";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.TESTING, result.get());
    }

    @Test
    void testSelectAgent_DeploymentContext() {
        // Given
        String context = "Docker containerization and Kubernetes deployment";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.DEPLOYMENT, result.get());
    }

    @Test
    void testSelectAgent_EventDrivenContext() {
        // Given
        String context = "Kafka event streaming and message broker configuration";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.EVENT_DRIVEN_ARCHITECTURE, result.get());
    }

    @Test
    void testSelectAgent_CICDContext() {
        // Given
        String context = "CI/CD pipeline setup with Jenkins and automated testing";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.CICD_PIPELINE, result.get());
    }

    @Test
    void testSelectAgent_ConfigurationContext() {
        // Given
        String context = "Spring Cloud Config and centralized configuration management";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.CONFIGURATION_MANAGEMENT, result.get());
    }

    @Test
    void testSelectAgent_ResilienceContext() {
        // Given
        String context = "Circuit breaker patterns and resilience engineering";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.RESILIENCE_ENGINEERING, result.get());
    }

    @Test
    void testSelectAgent_BusinessDomainContext() {
        // Given
        String context = "POS system inventory management and automotive domain";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.BUSINESS_DOMAIN, result.get());
    }

    @Test
    void testSelectAgent_EmptyContext() {
        // Given
        String context = "";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testSelectAgent_NullContext() {
        // When
        Optional<AgentType> result = selector.selectAgent(null);
        
        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testSelectAgent_UnrecognizedContext() {
        // Given
        String context = "Random unrelated content without technical keywords";
        
        // When
        Optional<AgentType> result = selector.selectAgent(context);
        
        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testSuggestAgents_ComplexContext() {
        // Given
        String context = "Spring Boot microservice with Docker deployment and security";
        
        // When
        List<AgentType> suggestions = selector.suggestAgents(context);
        
        // Then
        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.contains(AgentType.IMPLEMENTATION));
        assertTrue(suggestions.contains(AgentType.DEPLOYMENT));
        assertTrue(suggestions.contains(AgentType.SECURITY));
    }

    @Test
    void testSuggestAgents_SingleDomainContext() {
        // Given
        String context = "JWT authentication implementation";
        
        // When
        List<AgentType> suggestions = selector.suggestAgents(context);
        
        // Then
        assertFalse(suggestions.isEmpty());
        assertEquals(AgentType.SECURITY, suggestions.get(0));
    }

    @Test
    void testAnalyzeContextComplexity_SimpleContext() {
        // Given
        String context = "Spring Boot REST API";
        
        // When
        boolean isComplex = selector.analyzeContextComplexity(context);
        
        // Then
        assertFalse(isComplex);
    }

    @Test
    void testAnalyzeContextComplexity_ComplexContext() {
        // Given
        String context = "Spring Boot microservice with Docker deployment, JWT security, Kafka messaging, and circuit breakers";
        
        // When
        boolean isComplex = selector.analyzeContextComplexity(context);
        
        // Then
        assertTrue(isComplex);
    }
}
