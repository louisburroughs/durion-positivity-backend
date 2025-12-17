package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventDrivenArchitectureAgent to verify event-driven guidance
 * capabilities
 * Validates: Requirements REQ-012.1, REQ-012.2, REQ-012.3, REQ-012.4, REQ-012.5
 */
class EventDrivenArchitectureAgentTest {

    private EventDrivenArchitectureAgent eventDrivenAgent;

    @BeforeEach
    void setUp() {
        eventDrivenAgent = new EventDrivenArchitectureAgent();
    }

    @Test
    void testEventSchemaDesignGuidance() throws ExecutionException, InterruptedException {
        // Test event schema design and versioning guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to design event schemas with versioning for the POS system with event schema design versioning?",
                Map.of("service", "pos-events"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Event Schema Design"));
        assertTrue(response.guidance().contains("versioning"));
        assertTrue(response.guidance().contains("backward compatibility"));
        assertTrue(response.guidance().contains("Avro"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testIdempotentEventHandlerGuidance() throws ExecutionException, InterruptedException {
        // Test idempotent event handler implementation guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to implement idempotent event handlers with idempotent handlers event processing?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Idempotent Event Handler"));
        assertTrue(response.guidance().contains("@EventHandler"));
        assertTrue(response.guidance().contains("duplicate processing"));
        assertTrue(response.guidance().contains("idempotency key"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testKafkaConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test Kafka-specific configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to configure Kafka for Spring Boot microservices with kafka configuration spring boot?",
                Map.of("service", "pos-catalog"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Apache Kafka Configuration"));
        assertTrue(response.guidance().contains("spring-kafka"));
        assertTrue(response.guidance().contains("@KafkaListener"));
        assertTrue(response.guidance().contains("bootstrap-servers"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testSNSSQSConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test AWS SNS/SQS configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to configure AWS SNS and SQS for event processing with sns sqs aws messaging?",
                Map.of("service", "pos-inventory"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("AWS SNS/SQS Configuration"));
        assertTrue(response.guidance().contains("spring-cloud-aws"));
        assertTrue(response.guidance().contains("@SqsListener"));
        assertTrue(response.guidance().contains("AmazonSNS"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testRabbitMQConfigurationGuidance() throws ExecutionException, InterruptedException {
        // Test RabbitMQ configuration guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to configure RabbitMQ for microservices communication with rabbitmq messaging spring amqp?",
                Map.of("service", "pos-customer"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("RabbitMQ Configuration"));
        assertTrue(response.guidance().contains("spring-boot-starter-amqp"));
        assertTrue(response.guidance().contains("@RabbitListener"));
        assertTrue(response.guidance().contains("RabbitTemplate"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testEventSourcingGuidance() throws ExecutionException, InterruptedException {
        // Test event sourcing implementation guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to implement event sourcing patterns with event sourcing cqrs aggregate?",
                Map.of("service", "pos-order"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Event Sourcing Implementation"));
        assertTrue(response.guidance().contains("@AggregateRoot"));
        assertTrue(response.guidance().contains("Event Store"));
        assertTrue(response.guidance().contains("CQRS"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testEventFailureHandlingGuidance() throws ExecutionException, InterruptedException {
        // Test event failure handling and resilience guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How to handle event processing failures with failure handling retry dead letter?",
                Map.of("service", "pos-price"));

        CompletableFuture<AgentGuidanceResponse> future = eventDrivenAgent.provideGuidance(request);
        AgentGuidanceResponse response = future.get();

        assertTrue(response.isSuccessful());
        assertNotNull(response.guidance());
        assertTrue(response.guidance().contains("Event Failure Handling"));
        assertTrue(response.guidance().contains("Dead Letter Queue"));
        assertTrue(response.guidance().contains("@Retryable"));
        assertTrue(response.guidance().contains("exponential backoff"));
        assertTrue(response.confidence() > 0.9);
    }

    @Test
    void testAgentCapabilities() {
        // Verify agent capabilities
        assertTrue(eventDrivenAgent.getCapabilities().contains("event-schemas"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("kafka"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("sns-sqs"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("rabbitmq"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("event-sourcing"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("idempotency"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("message-brokers"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("async-communication"));
        assertTrue(eventDrivenAgent.getCapabilities().contains("event-handlers"));
    }

    @Test
    void testCanHandleEventDrivenRequests() {
        // Test that agent can handle event-driven domain requests
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "General event-driven architecture question with event driven patterns",
                Map.of());

        assertTrue(eventDrivenAgent.canHandle(request));
    }

    @Test
    void testCanHandleCapabilityBasedRequests() {
        // Test that agent can handle requests based on capabilities
        AgentConsultationRequest kafkaRequest = AgentConsultationRequest.create(
                "implementation",
                "I need Kafka configuration guidance with kafka messaging patterns",
                Map.of());

        assertTrue(eventDrivenAgent.canHandle(kafkaRequest));

        AgentConsultationRequest eventSourcingRequest = AgentConsultationRequest.create(
                "architecture",
                "Event sourcing implementation question with event sourcing cqrs patterns",
                Map.of());

        assertTrue(eventDrivenAgent.canHandle(eventSourcingRequest));
    }

    @Test
    void testAgentMetadata() {
        // Verify agent metadata
        assertEquals("event-driven-agent", eventDrivenAgent.getId());
        assertEquals("Event-Driven Architecture Agent", eventDrivenAgent.getName());
        assertEquals("event-driven", eventDrivenAgent.getDomain());
        assertTrue(eventDrivenAgent.getDependencies().contains("architecture-agent"));
        assertTrue(eventDrivenAgent.getDependencies().contains("resilience-agent"));
    }
}