package com.pos.agent.integration;

import com.pos.agent.framework.model.AgentType;
import com.pos.agent.framework.service.ServiceAgentMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServiceAgentMappingIntegrationTest {

    private ServiceAgentMapping serviceAgentMapping;

    @BeforeEach
    void setUp() {
        serviceAgentMapping = new ServiceAgentMapping();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pos-catalog", "pos-customer", "pos-inventory", "pos-vehicle-inventory",
            "pos-order", "pos-invoice", "pos-price", "pos-accounting",
            "pos-work-order", "pos-people", "pos-location", "pos-events",
            "pos-event-receiver", "pos-image", "pos-vehicle-fitment",
            "pos-vehicle-reference-data", "pos-vehicle-reference-integration",
            "pos-inquiry", "pos-shop-manager", "pos-api-gateway",
            "pos-security-service", "pos-service-discovery", "pos-agent-framework"
    })
    void testAllServicesHavePrimaryAgentMapping(String serviceName) {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent(serviceName);

        // Then
        assertTrue(primaryAgent.isPresent(),
                "Service " + serviceName + " should have a primary agent mapping");
        assertNotNull(primaryAgent.get(),
                "Primary agent for " + serviceName + " should not be null");
    }

    @Test
    void testCatalogServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-catalog");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-catalog");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.BUSINESS_DOMAIN, primaryAgent.get());
        assertFalse(suggestedAgents.isEmpty());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testCustomerServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-customer");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-customer");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.BUSINESS_DOMAIN, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testInventoryServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-inventory");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-inventory");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.IMPLEMENTATION, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.BUSINESS_DOMAIN));
    }

    @Test
    void testVehicleInventoryServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-vehicle-inventory");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-vehicle-inventory");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.BUSINESS_DOMAIN, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testSecurityServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-security-service");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-security-service");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.SECURITY, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testApiGatewayServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-api-gateway");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-api-gateway");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.INTEGRATION_GATEWAY, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.SECURITY));
    }

    @Test
    void testEventServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-events");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-events");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.EVENT_DRIVEN_ARCHITECTURE, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testEventReceiverServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-event-receiver");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-event-receiver");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.EVENT_DRIVEN_ARCHITECTURE, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.RESILIENCE_ENGINEERING));
    }

    @Test
    void testWorkOrderServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-work-order");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-work-order");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.BUSINESS_DOMAIN, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testVehicleFitmentServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-vehicle-fitment");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-vehicle-fitment");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.BUSINESS_DOMAIN, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testAgentFrameworkServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("pos-agent-framework");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("pos-agent-framework");

        // Then
        assertTrue(primaryAgent.isPresent());
        assertEquals(AgentType.ARCHITECTURE, primaryAgent.get());
        assertTrue(suggestedAgents.contains(AgentType.IMPLEMENTATION));
    }

    @Test
    void testUnknownServiceMapping() {
        // When
        Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent("unknown-service");
        List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents("unknown-service");

        // Then
        assertFalse(primaryAgent.isPresent());
        assertTrue(suggestedAgents.isEmpty());
    }

    @Test
    void testAllMappedServicesHaveSuggestedAgents() {
        // Given
        String[] allServices = {
                "pos-catalog", "pos-customer", "pos-inventory", "pos-vehicle-inventory",
                "pos-order", "pos-invoice", "pos-price", "pos-accounting",
                "pos-work-order", "pos-people", "pos-location", "pos-events",
                "pos-event-receiver", "pos-image", "pos-vehicle-fitment",
                "pos-vehicle-reference-data", "pos-vehicle-reference-integration",
                "pos-inquiry", "pos-shop-manager", "pos-api-gateway",
                "pos-security-service", "pos-service-discovery", "pos-agent-framework"
        };

        // When & Then
        for (String service : allServices) {
            List<AgentType> suggestedAgents = serviceAgentMapping.getSuggestedAgents(service);
            assertFalse(suggestedAgents.isEmpty(),
                    "Service " + service + " should have suggested agents");

            // Verify suggested agents don't include the primary agent
            Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent(service);
            if (primaryAgent.isPresent()) {
                assertFalse(suggestedAgents.contains(primaryAgent.get()),
                        "Suggested agents for " + service + " should not include the primary agent");
            }
        }
    }

    @Test
    void testBusinessDomainServicesMapping() {
        // Given
        String[] businessServices = {
                "pos-catalog", "pos-customer", "pos-vehicle-inventory",
                "pos-work-order", "pos-vehicle-fitment", "pos-inquiry", "pos-shop-manager"
        };

        // When & Then
        for (String service : businessServices) {
            Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent(service);
            assertTrue(primaryAgent.isPresent());
            assertEquals(AgentType.BUSINESS_DOMAIN, primaryAgent.get(),
                    "Business service " + service + " should have BUSINESS_DOMAIN as primary agent");
        }
    }

    @Test
    void testTechnicalServicesMapping() {
        // Given
        String[] technicalServices = {
                "pos-image"
        };

        // When & Then
        for (String service : technicalServices) {
            Optional<AgentType> primaryAgent = serviceAgentMapping.getPrimaryAgent(service);
            assertTrue(primaryAgent.isPresent());
            assertEquals(AgentType.IMPLEMENTATION, primaryAgent.get(),
                    "Technical service " + service + " should have IMPLEMENTATION as primary agent");
        }
    }

    @Test
    void testInfrastructureServicesMapping() {
        // When & Then
        assertEquals(AgentType.SECURITY,
                serviceAgentMapping.getPrimaryAgent("pos-security-service").orElse(null));
        assertEquals(AgentType.ARCHITECTURE,
                serviceAgentMapping.getPrimaryAgent("pos-service-discovery").orElse(null));
        assertEquals(AgentType.INTEGRATION_GATEWAY,
                serviceAgentMapping.getPrimaryAgent("pos-api-gateway").orElse(null));
        assertEquals(AgentType.EVENT_DRIVEN_ARCHITECTURE,
                serviceAgentMapping.getPrimaryAgent("pos-events").orElse(null));
        assertEquals(AgentType.EVENT_DRIVEN_ARCHITECTURE,
                serviceAgentMapping.getPrimaryAgent("pos-event-receiver").orElse(null));
    }
}
