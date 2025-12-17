package com.pos.agent.framework.routing;

import com.pos.agent.framework.model.AgentRoutingResult;
import com.pos.agent.framework.mapping.ServiceAgentMapping;
import com.positivity.agent.registry.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntelligentAgentRouterTest {

    @Mock
    private ServiceAgentMapping serviceAgentMapping;

    @Mock
    private ContextBasedAgentSelector contextSelector;

    @Mock
    private FallbackMechanism fallbackMechanism;

    @Mock
    private AgentRegistry agentRegistry;

    private IntelligentAgentRouter router;

    @BeforeEach
    void setUp() {
        router = new IntelligentAgentRouter(serviceAgentMapping, contextSelector, fallbackMechanism, agentRegistry);
    }

    @Test
    void testRouteRequest_WithValidServiceMapping() {
        // Given
        AgentRequest request = AgentRequest.builder()
                .type("service-specific")
                .targetService("pos-inventory")
                .description("Update inventory levels")
                .build();

        when(serviceAgentMapping.getPrimaryAgent("pos-inventory")).thenReturn(Optional.of(AgentType.IMPLEMENTATION));
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);

        // When
        AgentRoutingResult result = router.routeRequest(request);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(AgentType.IMPLEMENTATION, result.getSelectedAgent());
        assertEquals("pos-inventory", result.getTargetService());
        assertEquals("Primary agent mapping", result.getRoutingReason());
    }

    @Test
    void testRouteRequest_WithContextBasedFallback() {
        // Given
        AgentRequest request = AgentRequest.builder()
                .type("general")
                .description("Spring Boot microservice implementation")
                .build();

        when(serviceAgentMapping.getPrimaryAgent(any())).thenReturn(Optional.empty());
        when(contextSelector.selectAgent("Spring Boot microservice implementation"))
                .thenReturn(Optional.of(AgentType.IMPLEMENTATION));
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);

        // When
        AgentRoutingResult result = router.routeRequest(request);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(AgentType.IMPLEMENTATION, result.getSelectedAgent());
        assertEquals("Context-based selection", result.getRoutingReason());
    }

    @Test
    void testRouteRequest_WithFallbackMechanism() {
        // Given
        AgentRequest request = AgentRequest.builder()
                .type("unknown")
                .description("Unknown request type")
                .build();

        when(serviceAgentMapping.getPrimaryAgent(any())).thenReturn(Optional.empty());
        when(contextSelector.selectAgent(any())).thenReturn(Optional.empty());
        when(fallbackMechanism.getFallbackAgent(request)).thenReturn(Optional.of(AgentType.ARCHITECTURE));
        when(agentRegistry.isAgentAvailable(AgentType.ARCHITECTURE)).thenReturn(true);

        // When
        AgentRoutingResult result = router.routeRequest(request);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(AgentType.ARCHITECTURE, result.getSelectedAgent());
        assertEquals("Fallback mechanism", result.getRoutingReason());
    }

    @Test
    void testRouteRequest_NoAvailableAgent() {
        // Given
        AgentRequest request = AgentRequest.builder()
                .type("unknown")
                .description("Unknown request")
                .build();

        when(serviceAgentMapping.getPrimaryAgent(any())).thenReturn(Optional.empty());
        when(contextSelector.selectAgent(any())).thenReturn(Optional.empty());
        when(fallbackMechanism.getFallbackAgent(any())).thenReturn(Optional.empty());

        // When
        AgentRoutingResult result = router.routeRequest(request);

        // Then
        assertFalse(result.isSuccess());
        assertNull(result.getSelectedAgent());
        assertEquals("No available agent found", result.getErrorMessage());
    }

    @Test
    void testRouteToService_ValidService() {
        // Given
        when(serviceAgentMapping.getPrimaryAgent("pos-catalog")).thenReturn(Optional.of(AgentType.BUSINESS_DOMAIN));
        when(agentRegistry.isAgentAvailable(AgentType.BUSINESS_DOMAIN)).thenReturn(true);

        // When
        AgentRoutingResult result = router.routeToService("pos-catalog", "Catalog management");

        // Then
        assertTrue(result.isSuccess());
        assertEquals(AgentType.BUSINESS_DOMAIN, result.getSelectedAgent());
        assertEquals("pos-catalog", result.getTargetService());
    }

    @Test
    void testRouteToService_WithSuggestedAgentFallback() {
        // Given
        when(serviceAgentMapping.getPrimaryAgent("pos-catalog")).thenReturn(Optional.empty());
        when(serviceAgentMapping.getSuggestedAgents("pos-catalog"))
                .thenReturn(Arrays.asList(AgentType.IMPLEMENTATION, AgentType.BUSINESS_DOMAIN));
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);

        // When
        AgentRoutingResult result = router.routeToService("pos-catalog", "Catalog operations");

        // Then
        assertTrue(result.isSuccess());
        assertEquals(AgentType.IMPLEMENTATION, result.getSelectedAgent());
        assertEquals("pos-catalog", result.getTargetService());
    }

    @Test
    void testGetBestAgentForDomain_SpringBootDomain() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.IMPLEMENTATION)).thenReturn(true);

        // When
        Optional<AgentType> result = router.getBestAgentForDomain("spring-boot");

        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, result.get());
    }

    @Test
    void testGetBestAgentForDomain_SecurityDomain() {
        // Given
        when(agentRegistry.isAgentAvailable(AgentType.SECURITY)).thenReturn(true);

        // When
        Optional<AgentType> result = router.getBestAgentForDomain("security");

        // Then
        assertTrue(result.isPresent());
        assertEquals(AgentType.SECURITY, result.get());
    }

    @Test
    void testGetBestAgentForDomain_UnknownDomain() {
        // When
        Optional<AgentType> result = router.getBestAgentForDomain("unknown-domain");

        // Then
        assertFalse(result.isPresent());
    }
}
