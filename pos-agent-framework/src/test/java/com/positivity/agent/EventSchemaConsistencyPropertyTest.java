package com.positivity.agent;

import com.positivity.agent.impl.EventDrivenArchitectureAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

/**
 * Property-based test for event schema consistency
 * **Feature: agent-structure, Property 14: Event schema consistency**
 * **Validates: Requirements REQ-012.1, REQ-012.2**
 */
class EventSchemaConsistencyPropertyTest {

    private AgentRegistry registry;
    private EventDrivenArchitectureAgent eventDrivenAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        eventDrivenAgent = new EventDrivenArchitectureAgent();
        registry.registerAgent(eventDrivenAgent);
    }

    /**
     * Property 14: Event schema consistency
     * For any event-driven integration design, the Event-Driven Architecture Agent
     * should ensure
     * consistent event schemas and idempotent processing patterns
     */
    @Property(tries = 100)
    void eventSchemaConsistency(
            @ForAll("eventDrivenIntegrationRequests") AgentConsultationRequest request) {
        // Given: An event-driven architecture agent capable of schema guidance
        eventDrivenAgent = new EventDrivenArchitectureAgent();
        assertThat(eventDrivenAgent.isAvailable())
                .describedAs("Event-driven architecture agent should be available")
                .isTrue();

        // When: Requesting guidance for event-driven integration design
        AgentGuidanceResponse response = eventDrivenAgent.provideGuidance(request).join();

        // Then: The response should be successful
        assertThat(response.status())
                .describedAs("Event-driven guidance should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        // And: The response should contain event schema guidance
        String guidance = response.guidance();
        assertThat(guidance)
                .describedAs("Guidance should contain event schema recommendations")
                .containsAnyOf("event schema", "schema design", "schema versioning", "schema registry");

        // And: The response should include idempotent processing patterns
        assertThat(guidance)
                .describedAs("Guidance should include idempotent processing patterns")
                .containsAnyOf("idempotent", "idempotency", "duplicate handling", "event ID");

        // And: The response should include versioning strategies
        assertThat(guidance)
                .describedAs("Guidance should include versioning strategies")
                .containsAnyOf("versioning", "backward compatibility", "schema evolution", "semantic versioning");

        // And: The response should be timely
        assertThat(response.processingTime())
                .describedAs("Response time should be within acceptable limits")
                .isLessThan(Duration.ofSeconds(3));

        // And: The response should have high confidence for event-driven guidance
        assertThat(response.confidence())
                .describedAs("Confidence should be high for event-driven guidance")
                .isGreaterThan(0.8);
    }

    /**
     * Property 14b: Event handler idempotency guidance
     * For any event handler implementation request, the system should provide
     * idempotent processing patterns and error handling guidance
     */
    @Property(tries = 100)
    void eventHandlerIdempotencyGuidance(
            @ForAll("eventHandlerRequests") AgentConsultationRequest request) {
        // When: Requesting guidance for event handler implementation
        AgentGuidanceResponse response = eventDrivenAgent.provideGuidance(request).join();

        // Then: The response should be successful
        assertThat(response.status())
                .describedAs("Event handler guidance should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        // And: The response should contain idempotency patterns
        String guidance = response.guidance();
        assertThat(guidance)
                .describedAs("Guidance should contain idempotency patterns")
                .containsAnyOf("idempotent", "deduplication", "event ID", "processing status");

        // And: The response should include error handling patterns
        assertThat(guidance)
                .describedAs("Guidance should include error handling patterns")
                .containsAnyOf("error handling", "retry", "dead letter", "circuit breaker");

        // And: The response should include transactional patterns
        assertThat(guidance)
                .describedAs("Guidance should include transactional patterns")
                .containsAnyOf("transactional", "outbox pattern", "consistency", "@Transactional");
    }

    /**
     * Property 14c: Message broker configuration consistency
     * For any message broker configuration request, the system should provide
     * consistent configuration patterns based on the broker type
     */
    @Property(tries = 100)
    void messageBrokerConfigurationConsistency(
            @ForAll("messageBrokerRequests") AgentConsultationRequest request) {
        // When: Requesting guidance for message broker configuration
        AgentGuidanceResponse response = eventDrivenAgent.provideGuidance(request).join();

        // Then: The response should be successful
        assertThat(response.status())
                .describedAs("Message broker guidance should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        String guidance = response.guidance();
        String query = request.query().toLowerCase();

        // And: The response should contain broker-specific configuration
        if (query.contains("kafka")) {
            assertThat(guidance)
                    .describedAs("Kafka guidance should contain Kafka-specific configuration")
                    .containsAnyOf("kafka", "producer", "consumer", "topic", "partition");
        } else if (query.contains("sns") || query.contains("sqs")) {
            assertThat(guidance)
                    .describedAs("SNS/SQS guidance should contain AWS-specific configuration")
                    .containsAnyOf("sns", "sqs", "aws", "queue", "topic");
        } else if (query.contains("rabbitmq")) {
            assertThat(guidance)
                    .describedAs("RabbitMQ guidance should contain RabbitMQ-specific configuration")
                    .containsAnyOf("rabbitmq", "exchange", "queue", "binding", "amqp");
        }

        // And: The response should include reliability patterns
        assertThat(guidance)
                .describedAs("Guidance should include reliability patterns")
                .containsAnyOf("reliability", "durability", "acknowledgment", "persistence");
    }

    @Provide
    Arbitrary<AgentConsultationRequest> eventDrivenIntegrationRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        "event schema design for order processing",
                        "event versioning strategy for inventory updates",
                        "schema registry configuration for microservices",
                        "event schema evolution for customer events",
                        "backward compatibility for payment events",
                        "event schema validation for product catalog",
                        "schema versioning for user management events",
                        "event schema design for notification system",
                        "schema registry setup for distributed system",
                        "event schema consistency across services"),
                Arbitraries.of("event-driven", "integration", "schema", "events"),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10),
                        Arbitraries.of("value1", "value2", "value3")).ofMinSize(1).ofMaxSize(3))
                .as((query, domain, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> eventHandlerRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        "implement idempotent event handler for orders",
                        "event handler error handling patterns",
                        "idempotent processing for payment events",
                        "event handler retry mechanisms",
                        "transactional event processing",
                        "event handler deduplication logic",
                        "idempotent event processing patterns",
                        "event handler failure recovery",
                        "event processing consistency patterns",
                        "event handler transaction management"),
                Arbitraries.of("event-driven", "processing", "handlers"),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10),
                        Arbitraries.of("value1", "value2", "value3")).ofMinSize(1).ofMaxSize(3))
                .as((query, domain, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> messageBrokerRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        "kafka configuration for microservices",
                        "sns sqs setup for event streaming",
                        "rabbitmq configuration for messaging",
                        "kafka producer consumer setup",
                        "aws sns topic configuration",
                        "rabbitmq exchange and queue setup",
                        "kafka topic partitioning strategy",
                        "sqs dead letter queue configuration",
                        "rabbitmq message routing patterns",
                        "kafka consumer group management"),
                Arbitraries.of("event-driven", "messaging", "brokers"),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10),
                        Arbitraries.of("value1", "value2", "value3")).ofMinSize(1).ofMaxSize(3))
                .as((query, domain, context) -> AgentConsultationRequest.create(domain, query, context));
    }
}