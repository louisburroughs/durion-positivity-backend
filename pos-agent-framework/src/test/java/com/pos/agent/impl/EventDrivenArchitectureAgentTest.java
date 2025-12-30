package com.pos.agent.impl;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for event-driven architecture agent guidance using core API.
 * Tests event schemas, messaging systems, and event-driven patterns.
 * Validates: Requirements REQ-012.1, REQ-012.2, REQ-012.3, REQ-012.4, REQ-012.5
 */
class EventDrivenArchitectureAgentTest {

    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext securityContext = SecurityContext.builder()
            .jwtToken("valid-jwt-token-for-tests")
            .userId("event-driven-agent-tester")
            .roles(List.of("INTEGRATION_ARCHITECT", "EVENT_SPECIALIST"))
            .permissions(List.of("event.design", "messaging.configure"))
            .serviceId("pos-event-driven-agent-tests")
            .serviceType("test")
            .build();

    @Test
    void testEventSchemaDesignGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-events")
                .property("topic", "event-schema-design")
                .property("query", "How to design event schemas with versioning for the POS system?")
                .property("keywords", "event schema design versioning")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testIdempotentEventHandlerGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-order")
                .property("topic", "idempotent-handlers")
                .property("query", "How to implement idempotent event handlers?")
                .property("keywords", "idempotent handlers event processing")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testKafkaConfigurationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-catalog")
                .property("topic", "kafka")
                .property("query", "How to configure Kafka for Spring Boot microservices?")
                .property("keywords", "kafka configuration spring boot")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testSNSSQSConfigurationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-inventory")
                .property("topic", "sns-sqs")
                .property("query", "How to configure AWS SNS and SQS for event processing?")
                .property("keywords", "sns sqs aws messaging")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testRabbitMQConfigurationGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-customer")
                .property("topic", "rabbitmq")
                .property("query", "How to configure RabbitMQ for microservices communication?")
                .property("keywords", "rabbitmq messaging spring amqp")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testEventSourcingGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-order")
                .property("topic", "event-sourcing")
                .property("query", "How to implement event sourcing patterns?")
                .property("keywords", "event sourcing cqrs aggregate")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }

    @Test
    void testEventFailureHandlingGuidance() {
        AgentContext context = AgentContext.builder()
                .domain("event-driven")
                .property("service", "pos-price")
                .property("topic", "failure-handling")
                .property("query", "How to handle event processing failures?")
                .property("keywords", "failure handling retry dead letter")
                .build();

        AgentRequest request = AgentRequest.builder()
                .type("event-driven")
                .context(context)
                .securityContext(securityContext)
                .build();

        AgentResponse response = agentManager.processRequest(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }
}