package com.pos.agent.contract;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.impl.ResilienceEngineeringAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceEngineeringAgentContractTest {

    private Agent resilienceAgent;

    @BeforeEach
    void setUp() {
        resilienceAgent = new ResilienceEngineeringAgent();
    }

    @Test
    void shouldImplementAgentInterface() {
        assertInstanceOf(Agent.class, resilienceAgent);
    }

    // Identity/capabilities are outside the frozen core contract; validate
    // processing.

    @Test
    void shouldHandleCircuitBreakerRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("circuit-breaker")
                .description("Configure Resilience4j circuit breaker")
                .context("resilience4j")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertFalse(response.getOutput().trim().isEmpty());
    }

    @Test
    void shouldHandleRetryMechanismsRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("retry-mechanisms")
                .description("Implement exponential backoff retry")
                .context("exponential-backoff")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("retry"));
    }

    @Test
    void shouldHandleChaosEngineeringRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("chaos-engineering")
                .description("Setup chaos monkey for testing")
                .context("chaos-monkey")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().toLowerCase().contains("chaos"));
    }

    @Test
    void shouldHandleFailureInjectionRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("failure-injection")
                .description("Inject network latency for testing")
                .context("network-latency")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("Resilience pattern guidance"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .type("invalid-operation")
                .description("This should fail")
                .context("invalid")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = resilienceAgent.processRequest(null);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .type("circuit-breaker")
                .description("Test consistency")
                .context("cb-test")
                .build();

        AgentResponse response1 = resilienceAgent.processRequest(request);
        AgentResponse response2 = resilienceAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.getStatus(), response2.getStatus());
        assertNotNull(response1.getOutput());
        assertNotNull(response2.getOutput());
    }
}
