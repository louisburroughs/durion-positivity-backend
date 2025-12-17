package com.pos.agent.contract;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.impl.EventDrivenArchitectureAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EventDrivenArchitectureAgentContractTest {

    private Agent eventDrivenAgent;

    @BeforeEach
    void setUp() {
        eventDrivenAgent = new EventDrivenArchitectureAgent();
    }

    @Test
    void shouldImplementAgentInterface() {
        assertInstanceOf(Agent.class, eventDrivenAgent);
    }

    @Test
    void shouldReturnValidAgentId() {
        String agentId = eventDrivenAgent.getAgentId();
        assertNotNull(agentId);
        assertFalse(agentId.trim().isEmpty());
        assertEquals("event-driven-architecture", agentId);
    }

    @Test
    void shouldReturnValidCapabilities() {
        var capabilities = eventDrivenAgent.getCapabilities();
        assertNotNull(capabilities);
        assertFalse(capabilities.isEmpty());
        assertTrue(capabilities.contains("event-schema-design"));
        assertTrue(capabilities.contains("event-versioning"));
        assertTrue(capabilities.contains("idempotent-handlers"));
    }

    @Test
    void shouldHandleEventSchemaDesignRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("event-schema-design")
                .context("Design event schema for order processing")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertFalse(response.getContent().trim().isEmpty());
    }

    @Test
    void shouldHandleEventVersioningRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("event-versioning")
                .context("Version customer events for backward compatibility")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("versioning"));
    }

    @Test
    void shouldHandleIdempotentHandlerRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("idempotent-handlers")
                .context("Implement idempotent payment processing")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().contains("idempotent"));
    }

    @Test
    void shouldHandleKafkaConfigurationRequest() {
        AgentRequest request = AgentRequest.builder()
                .requestType("kafka-configuration")
                .context("Configure Kafka for high-throughput events")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertNotNull(response.getContent());
        assertTrue(response.getContent().toLowerCase().contains("kafka"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .requestType("unsupported-operation")
                .context("This should fail")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = eventDrivenAgent.processRequest(null);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .requestType("event-schema-design")
                .context("Test consistency")
                .build();

        AgentResponse response1 = eventDrivenAgent.processRequest(request);
        AgentResponse response2 = eventDrivenAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.isSuccess(), response2.isSuccess());
        assertNotNull(response1.getContent());
        assertNotNull(response2.getContent());
    }
}
