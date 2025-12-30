package com.pos.agent.impl;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentStatus;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for testing agent guidance using core API.
 * Tests various testing methodologies: unit testing, TDD, property-based
 * testing.
 */
class TestingAgentTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext securityContext = SecurityContext.builder()
            .jwtToken("valid-jwt-token-for-tests")
            .userId("testing-agent-tester")
            .roles(List.of("TESTING_EXPERT", "QUALITY_ASSURANCE"))
            .permissions(List.of("testing.guide", "quality.assess"))
            .serviceId("pos-testing-agent-tests")
            .serviceType("test")
            .build();

    @Test
    void shouldProvideUnitTestingGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("testing")
                .property("service", "pos-catalog")
                .property("topic", "unit-testing")
                .property("query", "How to implement unit tests with JUnit 5?")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void shouldProvideTDDGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("testing")
                .property("service", "pos-inventory")
                .property("topic", "tdd")
                .property("query", "How to implement Test-Driven Development?")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void shouldProvidePropertyBasedTestingGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("testing")
                .property("service", "pos-price")
                .property("topic", "property-based-testing")
                .property("query", "How to implement property-based testing with jqwik?")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void shouldProvideIntegrationTestingGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("testing")
                .property("service", "pos-order")
                .property("topic", "integration-testing")
                .property("query", "How to implement integration tests with TestContainers?")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("testing")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }
    
    @Test
    void shouldExtendAbstractAgentAndValidateRequests() {
        TestingAgent agent = new TestingAgent();
        AgentContext context = AgentContext.builder()
                .domain("testing")
                .property("service", "pos-catalog")
                .build();

        AgentRequest request = new AgentRequest();
        request.setType("testing");
        request.setDescription("How to implement unit tests?");
        request.setContext(context);

        AgentResponse response = agent.processRequest(request);

        assertEquals(AgentStatus.SUCCESS, response.getStatus());
        assertTrue(response.isSuccess());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("Testing pattern recommendation"));
        assertEquals(0.8, response.getConfidence());
    }
    
    @Test
    void shouldRejectInvalidRequestViaAbstractAgent() {
        TestingAgent agent = new TestingAgent();
        
        AgentResponse response = agent.processRequest(null);
        
        assertEquals(AgentStatus.FAILURE, response.getStatus());
        assertFalse(response.isSuccess());
        assertEquals("Invalid request: request is null", response.getOutput());
    }
}