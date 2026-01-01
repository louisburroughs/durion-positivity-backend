package com.pos.agent.contract;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.impl.EventDrivenArchitectureAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    // Identity/capabilities not part of core Agent contract; focus on processing
    // behavior.

    @Test
    void shouldHandleEventSchemaDesignRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("event-schema-design")
                .description("Design event schema for order processing")
                .context("schema-order")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertFalse(response.getOutput().trim().isEmpty());
    }

    @Test
    void shouldHandleEventVersioningRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("event-versioning")
                .description("Version customer events for backward compatibility")
                .context("versioning-customer")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("Event-driven pattern"));
    }

    @Test
    void shouldHandleIdempotentHandlerRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("idempotent-handlers")
                .description("Implement idempotent payment processing")
                .context("idempotency-payment")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().contains("idempotent"));
    }

    @Test
    void shouldHandleKafkaConfigurationRequest() {
        AgentRequest request = AgentRequest.builder()
                .type("kafka-configuration")
                .description("Configure Kafka for high-throughput events")
                .context("kafka-high-throughput")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutput());
        assertTrue(response.getOutput().toLowerCase().contains("kafka"));
    }

    @Test
    void shouldReturnErrorForUnsupportedRequestType() {
        AgentRequest request = AgentRequest.builder()
                .type("invalid-operation")
                .description("This should fail")
                .context("invalid")
                .build();

        AgentResponse response = eventDrivenAgent.processRequest(request);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldHandleNullRequest() {
        AgentResponse response = eventDrivenAgent.processRequest(null);

        assertNotNull(response);
        assertEquals("FAILURE", response.getStatus());
        assertNotNull(response.getOutput());
    }

    @Test
    void shouldReturnConsistentResponseFormat() {
        AgentRequest request = AgentRequest.builder()
                .type("event-schema-design")
                .description("Test consistency")
                .context("schema-test")
                .build();

        AgentResponse response1 = eventDrivenAgent.processRequest(request);
        AgentResponse response2 = eventDrivenAgent.processRequest(request);

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(response1.getStatus(), response2.getStatus());
        assertNotNull(response1.getOutput());
        assertNotNull(response2.getOutput());
    }
}
