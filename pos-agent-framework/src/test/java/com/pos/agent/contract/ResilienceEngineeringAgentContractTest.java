package com.pos.agent.contract;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.impl.ResilienceEngineeringAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
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

    @Test
    void shouldReturnValidAgentId() {
        String agentId = resilienceAgent.getAgentId();
        assertNotNull(agentId);
        assertFalse(agentId.trim().isEmpty());
        assertEquals("resilience-engineering", agentId);
    }

    @Test
    void shouldReturnValidCapabilities() {
        var capabilities = resilienceAgent.getCapabilities();
        assertNotNull(capabilities);
        assertFalse(capabilities.isEmpty());
        assertTrue(capabilities.contains("circuit-breaker"));
        assertTrue(capabilities.contains("retry-mechanisms"));
        assertTrue(capabilities.contains("chaos-engineering"));
        assertTrue(capabilities.contains("failure-injection"));
    }

    @Test
    void shouldHandleCircuitBreakerRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("circuit-breaker")
                .context("Configure Resilience4j circuit breaker")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertFalse(response.getContent().trim().isEmpty());
    }

    @Test
    void shouldHandleRetryMechanismsRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("retry-mechanisms")
                .context("Implement exponential backoff retry")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("retry"));
    }

    @Test
    void shouldHandleChaosEngineeringRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("chaos-engineering")
                .context("Setup chaos monkey for testing")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().toLowerCase().contains("chaos"));
    }

    @Test
    void shouldHandleFailureInjectionRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("failure-injection")
                .context("Inject network latency for testing")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("failure"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .requestType("unsupported-operation")
                .context("This should fail")
                .build();

        AgentResponse response = resilienceAgent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = resilienceAgent.processRequest(null);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .requestType("circuit-breaker")
                .context("Test consistency")
                .build();

        AgentResponse response1 = resilienceAgent.processRequest(request);
        AgentResponse response2 = resilienceAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.isSuccess(), response2.isSuccess());
        assertNotNull(response1.getContent());
        assertNotNull(response2.getContent());
    }
}
