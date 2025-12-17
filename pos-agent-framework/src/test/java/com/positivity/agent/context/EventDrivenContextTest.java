package com.positivity.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventDrivenContext
 * Tests context model for event-driven architecture scenarios
 * 
 * Requirements: REQ-012.1 - Event schema design and versioning guidance
 */
class EventDrivenContextTest {

    private EventDrivenContext context;
    private final String contextId = "test-context-123";
    private final String sessionId = "test-session-456";

    @BeforeEach
    void setUp() {
        context = new EventDrivenContext(contextId, sessionId);
    }

    @Test
    @DisplayName("Should initialize EventDrivenContext with correct properties")
    void testInitialization() {
        // Assert
        assertEquals(contextId, context.getContextId());
        assertEquals(sessionId, context.getSessionId());
        assertNotNull(context.getCreatedAt());
        assertNotNull(context.getLastUpdated());
        assertTrue(context.getCreatedAt().isBefore(Instant.now().plusSeconds(1)));
        assertTrue(context.getLastUpdated().isBefore(Instant.now().plusSeconds(1)));
        
        // Verify empty collections
        assertTrue(context.getMessageBrokers().isEmpty());
        assertTrue(context.getEventSchemas().isEmpty());
        assertTrue(context.getEventHandlers().isEmpty());
        assertTrue(context.getSchemaVersions().isEmpty());
        assertTrue(context.getEventSources().isEmpty());
        assertTrue(context.getEventConsumers().isEmpty());
        assertTrue(context.getBrokerConfigurations().isEmpty());
        assertTrue(context.getIdempotencyPatterns().isEmpty());
        assertTrue(context.getErrorHandlingStrategies().isEmpty());
        assertTrue(context.getDeadLetterQueues().isEmpty());
        assertTrue(context.getEventStores().isEmpty());
        assertTrue(context.getProjections().isEmpty());
        assertTrue(context.getSagas().isEmpty());
        
        // Verify initial validation state
        assertFalse(context.isValid());
        assertFalse(context.hasEventSourcing());
        assertFalse(context.hasResiliencePatterns());
    }

    @Test
    @DisplayName("Should add message brokers with configurations")
    void testAddMessageBroker() {
        // Arrange
        Map<String, Object> kafkaConfig = Map.of(
            "type", "distributed-streaming",
            "partitions", 12,
            "replication-factor", 3
        );
        
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addMessageBroker("kafka", kafkaConfig);
        
        // Assert
        assertEquals(1, context.getMessageBrokers().size());
        assertTrue(context.getMessageBrokers().contains("kafka"));
        assertEquals(kafkaConfig, context.getBrokerConfigurations().get("kafka"));
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.isValid());
        
        // Test duplicate prevention
        context.addMessageBroker("kafka", Map.of("different", "config"));
        assertEquals(1, context.getMessageBrokers().size());
        assertEquals(kafkaConfig, context.getBrokerConfigurations().get("kafka")); // Original config preserved
    }

    @Test
    @DisplayName("Should add multiple message brokers")
    void testAddMultipleMessageBrokers() {
        // Act
        context.addMessageBroker("kafka", Map.of("type", "streaming"));
        context.addMessageBroker("rabbitmq", Map.of("type", "message-queue"));
        context.addMessageBroker("aws-sns-sqs", Map.of("type", "cloud-messaging"));
        
        // Assert
        assertEquals(3, context.getMessageBrokers().size());
        assertTrue(context.getMessageBrokers().contains("kafka"));
        assertTrue(context.getMessageBrokers().contains("rabbitmq"));
        assertTrue(context.getMessageBrokers().contains("aws-sns-sqs"));
        assertEquals(3, context.getBrokerConfigurations().size());
        assertTrue(context.isValid());
    }

    @Test
    @DisplayName("Should add event schemas with versioning")
    void testAddEventSchema() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addEventSchema("user-events", "v1.0");
        context.addEventSchema("order-events", "v2.1");
        
        // Assert
        assertEquals(2, context.getEventSchemas().size());
        assertTrue(context.getEventSchemas().contains("user-events"));
        assertTrue(context.getEventSchemas().contains("order-events"));
        assertEquals("v1.0", context.getSchemaVersions().get("user-events"));
        assertEquals("v2.1", context.getSchemaVersions().get("order-events"));
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.isValid());
    }

    @Test
    @DisplayName("Should update schema versions")
    void testUpdateSchemaVersion() {
        // Arrange
        context.addEventSchema("user-events", "v1.0");
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.updateSchemaVersion("user-events", "v1.1");
        
        // Assert
        assertEquals("v1.1", context.getSchemaVersions().get("user-events"));
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        
        // Test updating non-existent schema (should not crash)
        context.updateSchemaVersion("non-existent", "v2.0");
        assertNull(context.getSchemaVersions().get("non-existent"));
    }

    @Test
    @DisplayName("Should add event handlers with idempotency patterns")
    void testAddEventHandler() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addEventHandler("user-handler", "idempotency-key");
        context.addEventHandler("order-handler", "message-id");
        context.addEventHandler("notification-handler", null); // No idempotency pattern
        
        // Assert
        assertEquals(3, context.getEventHandlers().size());
        assertTrue(context.getEventHandlers().contains("user-handler"));
        assertTrue(context.getEventHandlers().contains("order-handler"));
        assertTrue(context.getEventHandlers().contains("notification-handler"));
        
        assertEquals(2, context.getIdempotencyPatterns().size());
        assertTrue(context.getIdempotencyPatterns().contains("idempotency-key"));
        assertTrue(context.getIdempotencyPatterns().contains("message-id"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.isValid());
        assertTrue(context.hasResiliencePatterns());
    }

    @Test
    @DisplayName("Should add event sourcing components")
    void testAddEventSourcingComponents() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addEventStore("user-event-store");
        context.addProjection("user-profile-projection");
        context.addSaga("order-processing-saga");
        
        // Assert
        assertEquals(1, context.getEventStores().size());
        assertTrue(context.getEventStores().contains("user-event-store"));
        
        assertEquals(1, context.getProjections().size());
        assertTrue(context.getProjections().contains("user-profile-projection"));
        
        assertEquals(1, context.getSagas().size());
        assertTrue(context.getSagas().contains("order-processing-saga"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasEventSourcing());
        assertTrue(context.isValid());
    }

    @Test
    @DisplayName("Should add error handling and resilience patterns")
    void testAddErrorHandlingPatterns() {
        // Arrange
        Instant beforeUpdate = context.getLastUpdated();
        
        // Act
        context.addErrorHandlingStrategy("retry-with-backoff");
        context.addErrorHandlingStrategy("circuit-breaker");
        context.addDeadLetterQueue("failed-user-events", "user-dlq");
        context.addDeadLetterQueue("failed-order-events", "order-dlq");
        
        // Assert
        assertEquals(2, context.getErrorHandlingStrategies().size());
        assertTrue(context.getErrorHandlingStrategies().contains("retry-with-backoff"));
        assertTrue(context.getErrorHandlingStrategies().contains("circuit-breaker"));
        
        assertEquals(2, context.getDeadLetterQueues().size());
        assertEquals("user-dlq", context.getDeadLetterQueues().get("failed-user-events"));
        assertEquals("order-dlq", context.getDeadLetterQueues().get("failed-order-events"));
        
        assertTrue(context.getLastUpdated().isAfter(beforeUpdate));
        assertTrue(context.hasResiliencePatterns());
    }

    @Test
    @DisplayName("Should prevent duplicate entries")
    void testDuplicatePrevention() {
        // Act - Add same items multiple times
        context.addEventStore("event-store");
        context.addEventStore("event-store");
        
        context.addProjection("projection");
        context.addProjection("projection");
        
        context.addSaga("saga");
        context.addSaga("saga");
        
        context.addErrorHandlingStrategy("strategy");
        context.addErrorHandlingStrategy("strategy");
        
        // Assert - Should only have one of each
        assertEquals(1, context.getEventStores().size());
        assertEquals(1, context.getProjections().size());
        assertEquals(1, context.getSagas().size());
        assertEquals(1, context.getErrorHandlingStrategies().size());
    }

    @Test
    @DisplayName("Should validate context states correctly")
    void testContextValidation() {
        // Initially invalid
        assertFalse(context.isValid());
        assertFalse(context.hasEventSourcing());
        assertFalse(context.hasResiliencePatterns());
        
        // Valid with message broker
        context.addMessageBroker("kafka", Map.of());
        assertTrue(context.isValid());
        assertFalse(context.hasEventSourcing());
        assertFalse(context.hasResiliencePatterns());
        
        // Valid with event schema
        EventDrivenContext schemaContext = new EventDrivenContext("schema-ctx", "session");
        schemaContext.addEventSchema("test-schema", "v1.0");
        assertTrue(schemaContext.isValid());
        
        // Valid with event handler
        EventDrivenContext handlerContext = new EventDrivenContext("handler-ctx", "session");
        handlerContext.addEventHandler("test-handler", "idempotency");
        assertTrue(handlerContext.isValid());
        
        // Has event sourcing
        context.addEventStore("event-store");
        assertTrue(context.hasEventSourcing());
        
        // Has resilience patterns
        context.addErrorHandlingStrategy("retry");
        assertTrue(context.hasResiliencePatterns());
    }

    @Test
    @DisplayName("Should return defensive copies of collections")
    void testDefensiveCopies() {
        // Arrange
        context.addMessageBroker("kafka", Map.of("type", "streaming"));
        context.addEventSchema("test-schema", "v1.0");
        context.addEventHandler("test-handler", "idempotency");
        
        // Act - Get collections and try to modify them
        context.getMessageBrokers().add("should-not-be-added");
        context.getEventSchemas().add("should-not-be-added");
        context.getEventHandlers().add("should-not-be-added");
        context.getSchemaVersions().put("should-not", "be-added");
        context.getBrokerConfigurations().put("should-not", "be-added");
        context.getIdempotencyPatterns().add("should-not-be-added");
        
        // Assert - Original collections should be unchanged
        assertEquals(1, context.getMessageBrokers().size());
        assertEquals(1, context.getEventSchemas().size());
        assertEquals(1, context.getEventHandlers().size());
        assertEquals(1, context.getSchemaVersions().size());
        assertEquals(1, context.getBrokerConfigurations().size());
        assertEquals(1, context.getIdempotencyPatterns().size());
        
        assertTrue(context.getMessageBrokers().contains("kafka"));
        assertFalse(context.getMessageBrokers().contains("should-not-be-added"));
    }

    @Test
    @DisplayName("Should have meaningful toString representation")
    void testToString() {
        // Arrange
        context.addMessageBroker("kafka", Map.of());
        context.addEventSchema("user-events", "v1.0");
        context.addEventHandler("user-handler", "idempotency");
        
        // Act
        String toString = context.toString();
        
        // Assert
        assertNotNull(toString);
        assertTrue(toString.contains("EventDrivenContext"));
        assertTrue(toString.contains(contextId));
        assertTrue(toString.contains(sessionId));
        assertTrue(toString.contains("brokers=1"));
        assertTrue(toString.contains("schemas=1"));
        assertTrue(toString.contains("handlers=1"));
    }

    @Test
    @DisplayName("Should handle complex event-driven scenario")
    void testComplexEventDrivenScenario() {
        // Arrange & Act - Build a complex event-driven context
        
        // Message brokers
        context.addMessageBroker("kafka", Map.of(
            "type", "distributed-streaming",
            "partitions", 12,
            "replication-factor", 3
        ));
        context.addMessageBroker("rabbitmq", Map.of(
            "type", "message-queue",
            "durable", true
        ));
        
        // Event schemas with versioning
        context.addEventSchema("user-registered", "v1.0");
        context.addEventSchema("order-placed", "v2.1");
        context.addEventSchema("payment-processed", "v1.5");
        context.updateSchemaVersion("user-registered", "v1.1");
        
        // Event handlers with idempotency
        context.addEventHandler("user-registration-handler", "user-id");
        context.addEventHandler("order-processing-handler", "order-id");
        context.addEventHandler("payment-handler", "payment-id");
        context.addEventHandler("notification-handler", null);
        
        // Event sourcing
        context.addEventStore("user-event-store");
        context.addEventStore("order-event-store");
        context.addProjection("user-profile-projection");
        context.addProjection("order-summary-projection");
        context.addSaga("order-fulfillment-saga");
        context.addSaga("payment-processing-saga");
        
        // Error handling and resilience
        context.addErrorHandlingStrategy("exponential-backoff");
        context.addErrorHandlingStrategy("circuit-breaker");
        context.addErrorHandlingStrategy("bulkhead-isolation");
        context.addDeadLetterQueue("failed-user-events", "user-dlq");
        context.addDeadLetterQueue("failed-order-events", "order-dlq");
        context.addDeadLetterQueue("failed-payment-events", "payment-dlq");
        
        // Assert - Verify complex scenario
        assertEquals(2, context.getMessageBrokers().size());
        assertEquals(3, context.getEventSchemas().size());
        assertEquals(4, context.getEventHandlers().size());
        assertEquals(3, context.getIdempotencyPatterns().size());
        assertEquals(2, context.getEventStores().size());
        assertEquals(2, context.getProjections().size());
        assertEquals(2, context.getSagas().size());
        assertEquals(3, context.getErrorHandlingStrategies().size());
        assertEquals(3, context.getDeadLetterQueues().size());
        
        // Verify schema versioning
        assertEquals("v1.1", context.getSchemaVersions().get("user-registered"));
        assertEquals("v2.1", context.getSchemaVersions().get("order-placed"));
        assertEquals("v1.5", context.getSchemaVersions().get("payment-processed"));
        
        // Verify validation states
        assertTrue(context.isValid());
        assertTrue(context.hasEventSourcing());
        assertTrue(context.hasResiliencePatterns());
        
        // Verify broker configurations
        Map<String, Object> kafkaConfig = (Map<String, Object>) context.getBrokerConfigurations().get("kafka");
        assertEquals("distributed-streaming", kafkaConfig.get("type"));
        assertEquals(12, kafkaConfig.get("partitions"));
        assertEquals(3, kafkaConfig.get("replication-factor"));
        
        Map<String, Object> rabbitmqConfig = (Map<String, Object>) context.getBrokerConfigurations().get("rabbitmq");
        assertEquals("message-queue", rabbitmqConfig.get("type"));
        assertEquals(true, rabbitmqConfig.get("durable"));
    }
}