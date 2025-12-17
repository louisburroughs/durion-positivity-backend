package com.pos.agent.framework.routing;

import com.pos.agent.framework.model.AgentRequest;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.framework.registry.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackMechanismTest {

    @Mock
    private ContextBasedAgentSelector contextSelector;
    
    @Mock
    private AgentRegistry agentRegistry;
    
    private FallbackMechanism fallbackMechanism;

    @BeforeEach
    void setUp() {
        fallbackMechanism = new FallbackMechanism(contextSelector, agentRegistry);
    }

    @Test
    void testGetFallbackAgent_ContextBasedFallback() {
        // Given
        AgentRequest request = AgentRequest.builder()
            .description("Spring Boot implementation")
            .build();
        
        when(contextSelector.selectAgent("Spring Boot implementation")).thenReturn(Optional.of(AgentType.IMPLEMENTATION));
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getFallbackAgent(request);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetFallbackAgent_DomainBasedFallback() {
        // Given
        AgentRequest request = AgentRequest.builder()
            .type("spring-boot")
            .description("Unknown context")
            .build();
        
        when(contextSelector.selectAgent(any())).thenReturn(Optional.empty());
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getFallbackAgent(request);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetFallbackAgent_UniversalFallback() {
        // Given
        AgentRequest request = AgentRequest.builder()
            .type("unknown")
            .description("Unknown context")
            .build();
        
        when(contextSelector.selectAgent(any())).thenReturn(Optional.empty());
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getFallbackAgent(request);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testGetFallbackAgent_NoAvailableAgent() {
        // Given
        AgentRequest request = AgentRequest.builder()
            .type("unknown")
            .description("Unknown context")
            .build();
        
        when(contextSelector.selectAgent(any())).thenReturn(Optional.empty());
        when(agentRegistry.isAgentAvailable(any())).thenReturn(false);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getFallbackAgent(request);
        
        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testGetAgentFailureFallback_ImplementationAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.IMPLEMENTATION);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testGetAgentFailureFallback_SecurityAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.SECURITY);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetAgentFailureFallback_DeploymentAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.DEPLOYMENT);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testGetAgentFailureFallback_TestingAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.TESTING);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetAgentFailureFallback_EventDrivenAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.EVENT_DRIVEN_ARCHITECTURE);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testGetAgentFailureFallback_CICDAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.DEPLOYMENT)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.CICD_PIPELINE);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.DEPLOYMENT, result.get());
    }

    @Test
    void testGetAgentFailureFallback_ConfigurationAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.CONFIGURATION_MANAGEMENT);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetAgentFailureFallback_ResilienceAgent() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(AgentType.RESILIENCE_ENGINEERING);
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testGetAgentFailureFallback_UnknownAgent() {
        // When
        Optional<AgentType> result = fallbackMechanism.getAgentFailureFallback(null);
        
        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testGetDomainBasedFallback_SpringBootDomain() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getDomainBasedFallback("spring-boot");
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetDomainBasedFallback_SecurityDomain() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.SECURITY)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getDomainBasedFallback("security");
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.SECURITY, result.get());
    }

    @Test
    void testGetDomainBasedFallback_UnknownDomain() {
        // When
        Optional<AgentType> result = fallbackMechanism.getDomainBasedFallback("unknown-domain");
        
        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testGetUniversalFallback_ArchitectureAvailable() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getUniversalFallback();
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.ARCHITECTURE, result.get());
    }

    @Test
    void testGetUniversalFallback_ImplementationAvailable() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(false);
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getUniversalFallback();
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetUniversalFallback_DocumentationAvailable() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(false);
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(false);
        when(agentRegistry.isAgentAvailable(AgentType.DOCUMENTATION)).thenReturn(true);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getUniversalFallback();
        
        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.DOCUMENTATION, result.get());
    }

    @Test
    void testGetUniversalFallback_NoUniversalAgentsAvailable() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(false);
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(false);
        when(agentRegistry.isAgentAvailable(AgentType.DOCUMENTATION)).thenReturn(false);
        
        // When
        Optional<AgentType> result = fallbackMechanism.getUniversalFallback();
        
        // Then
        assertFalse(result.isPresent());
    }
}
