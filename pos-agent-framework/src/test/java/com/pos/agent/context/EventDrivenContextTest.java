package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for EventDrivenContext class.
 * Tests event-driven architecture aspects including:
 * - Message brokers and event handling
 * - Dead letter queues and event stores
 * - Event sourcing patterns
 * - Saga patterns for distributed transactions
 * - Choreography vs orchestration patterns
 */
@DisplayName("EventDrivenContext")
class EventDrivenContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create EventDrivenContext using builder")
        void shouldCreateEventDrivenContextUsingBuilder() {
            // When
            EventDrivenContext context = EventDrivenContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'event-driven'")
        void shouldSetDefaultAgentDomain() {
            // When
            EventDrivenContext context = EventDrivenContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("event-driven");
        }

        @Test
        @DisplayName("should set default contextType to 'event-driven-context'")
        void shouldSetDefaultContextType() {
            // When
            EventDrivenContext context = EventDrivenContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("event-driven-context");
        }

        @Test
        @DisplayName("should add message broker with configuration")
        void shouldAddMessageBroker() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addMessageBroker("Kafka", Map.of("brokers", "kafka-1:9092", "replication-factor", 3))
                    .build();

            // Then
            assertThat(context.getMessageBrokers()).contains("Kafka");
            assertThat(context.getBrokerConfigurations()).containsKey("Kafka");
        }

        @Test
        @DisplayName("should add event handler with type")
        void shouldAddEventHandler() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventHandler("OrderCreatedHandler", "idempotent-key")
                    .addHandlerType("OrderCreatedHandler", "OrderCreatedEvent")
                    .build();

            // Then
            assertThat(context.getEventHandlers()).contains("OrderCreatedHandler");
            assertThat(context.getHandlerTypes()).containsEntry("OrderCreatedHandler", "OrderCreatedEvent");
        }

        @Test
        @DisplayName("should add event store")
        void shouldAddEventStore() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventStore("PostgreSQL EventStore")
                    .build();

            // Then
            assertThat(context.getEventStores()).contains("PostgreSQL EventStore");
        }

        @Test
        @DisplayName("should add event schema")
        void shouldAddEventSchema() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventStore("EventStore")
                    .addEventSchema("EventStore", "Avro")
                    .build();

            // Then
            assertThat(context.getSchemaVersions()).containsEntry("EventStore", "Avro");
        }

        @Test
        @DisplayName("should add event source")
        void shouldAddEventSource() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventSource("order-service")
                    .build();

            // Then
            assertThat(context.getEventSources()).contains("order-service");
        }

        @Test
        @DisplayName("should add event consumer")
        void shouldAddEventConsumer() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventConsumer("email-notification-consumer")
                    .build();

            // Then
            assertThat(context.getEventConsumers()).contains("email-notification-consumer");
        }

        @Test
        @DisplayName("should add dead letter queue")
        void shouldAddDeadLetterQueue() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addMessageBroker("Kafka", Map.of("brokers", "kafka-1:9092"))
                    .addDeadLetterQueue("failed-events", "max-retries=3")
                    .build();

            // Then
            assertThat(context.getDeadLetterQueues()).contains("failed-events");
            assertThat(context.getDeadLetterQueuesMap()).containsEntry("failed-events", "max-retries=3");
        }

        @Test
        @DisplayName("should add saga pattern")
        void shouldAddSagaPattern() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addSaga("OrderSaga")
                    .build();

            // Then
            assertThat(context.getSagas()).contains("OrderSaga");
        }

        @Test
        @DisplayName("should add choreography pattern")
        void shouldAddChoreographyPattern() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addChoreography("OrderFulfillmentChoreography")
                    .build();

            // Then
            assertThat(context.getChoreography()).contains("OrderFulfillmentChoreography");
        }

        @Test
        @DisplayName("should add orchestration pattern")
        void shouldAddOrchestrationPattern() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addOrchestration("OrderOrchestrator")
                    .build();

            // Then
            assertThat(context.getOrchestration()).contains("OrderOrchestrator");
        }

        @Test
        @DisplayName("should add idempotency pattern")
        void shouldAddIdempotencyPattern() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventHandler("OrderHandler", "idempotent-key")
                    .build();

            // Then
            assertThat(context.getIdempotencyPatterns()).contains("idempotent-key");
        }

        @Test
        @DisplayName("should add error handling strategy")
        void shouldAddErrorHandlingStrategy() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addErrorHandlingStrategy("RetryWithBackoff")
                    .build();

            // Then
            assertThat(context.getErrorHandlingStrategies()).contains("RetryWithBackoff");
        }

        @Test
        @DisplayName("should add projection")
        void shouldAddProjection() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .addProjection("OrderView")
                    .build();

            // Then
            assertThat(context.getProjections()).contains("OrderView");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of message brokers")
        void shouldReturnDefensiveCopyOfMessageBrokers() {
            // Given
            EventDrivenContext context = EventDrivenContext.builder()
                    .addMessageBroker("Broker1", Map.of("active", true))
                    .build();

            // When
            var brokers = context.getMessageBrokers();
            brokers.add("Hacked");

            // Then
            assertThat(context.getMessageBrokers()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of event handlers")
        void shouldReturnDefensiveCopyOfEventHandlers() {
            // Given
            EventDrivenContext context = EventDrivenContext.builder()
                    .addEventHandler("Handler1", "async")
                    .build();

            // When
            var handlers = context.getEventHandlers();
            handlers.add("Hacked");

            // Then
            assertThat(context.getEventHandlers()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of sagas")
        void shouldReturnDefensiveCopyOfSagas() {
            // Given
            EventDrivenContext context = EventDrivenContext.builder()
                    .addSaga("Saga1")
                    .build();

            // When
            var sagas = context.getSagas();
            sagas.add("Hacked");

            // Then
            assertThat(context.getSagas()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive event-driven architecture context")
        void shouldCreateComprehensiveEventDrivenContext() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .description("E-Commerce Event-Driven Architecture")
                    .addMessageBroker("Kafka", Map.of("brokers", "kafka-1:9092,kafka-2:9092", "replication-factor", 3))
                    .addDeadLetterQueue("failed-events", "max-retries=5,backoff=exponential")
                    .addEventStore("PostgreSQL EventStore")
                    .addEventSchema("PostgreSQL EventStore", "Avro")
                    .addEventSource("OrderService")
                    .addEventSource("PaymentService")
                    .addEventSource("InventoryService")
                    .addEventHandler("OrderCreatedHandler", "async")
                    .addEventHandler("PaymentProcessedHandler", "sync")
                    .addEventHandler("InventoryUpdatedHandler", "async")
                    .addEventConsumer("NotificationService")
                    .addEventConsumer("ReportingService")
                    .addSaga("OrderSaga")
                    .addChoreography("OrderFulfillmentChoreography")
                    .addErrorHandlingStrategy("OrderHandler")
                    .addErrorHandlingStrategy("PaymentHandler")
                    .addProjection("OrderProjection")
                    .addProjection("CustomerProjection")
                    .build();

            // Then
            assertThat(context.getMessageBrokers()).hasSize(1);
            assertThat(context.getEventStores()).hasSize(1);
            assertThat(context.getEventSources()).hasSize(3);
            assertThat(context.getEventHandlers()).hasSize(3);
            assertThat(context.getEventConsumers()).hasSize(2);
            assertThat(context.getSagas()).hasSize(1);
            assertThat(context.getChoreography()).hasSize(1);
            assertThat(context.getIdempotencyPatterns()).hasSize(2);
            assertThat(context.getErrorHandlingStrategies()).hasSize(2);
            assertThat(context.getProjections()).hasSize(2);
        }

        @Test
        @DisplayName("should create event-driven context with saga pattern for distributed transactions")
        void shouldCreateSagaPatternContext() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .description("Order Processing Saga")
                    .addSaga("OrderSaga")
                    .addSaga("PaymentSaga")
                    .addSaga("ShippingSaga")
                    .addChoreography("OrderFulfillment")
                    .addEventSource("OrderService")
                    .addEventHandler("OrderCreatedHandler", "async")
                    .addEventConsumer("PaymentService")
                    .addEventConsumer("ShippingService")
                    .addErrorHandlingStrategy("OrderHandler")
                    .build();

            // Then
            assertThat(context.getSagas()).hasSize(3);
            assertThat(context.getChoreography()).hasSize(1);
        }

        @Test
        @DisplayName("should create event-driven context with orchestration pattern")
        void shouldCreateOrchestrationPatternContext() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .description("Orchestration-Based Order Processing")
                    .addOrchestration("OrderOrchestrator")
                    .addOrchestration("PaymentOrchestrator")
                    .addEventSource("OrderService")
                    .addEventHandler("OrderCreatedHandler", "sync")
                    .addEventConsumer("PaymentService")
                    .addEventConsumer("InventoryService")
                    .addEventConsumer("ShippingService")
                    .build();

            // Then
            assertThat(context.getOrchestration()).hasSize(2);
        }

        @Test
        @DisplayName("should create event-driven context with multiple event stores and schemas")
        void shouldCreateMultiEventStoreContext() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .description("Multi-Store Event Sourcing")
                    .addEventStore("PostgreSQL EventStore")
                    .addEventSchema("PostgreSQL EventStore", "Avro")
                    .addEventStore("MongoDB EventStore")
                    .addEventSchema("MongoDB EventStore", "JSON")
                    .addEventStore("EventStoreDB")
                    .addEventSchema("EventStoreDB", "Protocol Buffers")
                    .build();

            // Then
            assertThat(context.getEventStores()).hasSize(3);
            assertThat(context.getEventSchemas()).hasSize(3);
        }

        @Test
        @DisplayName("should create event-driven context with comprehensive error handling")
        void shouldCreateComprehensiveErrorHandlingContext() {
            // When
            EventDrivenContext context = EventDrivenContext.builder()
                    .description("Event Error Handling Strategy")
                    .addDeadLetterQueue("failed-events", "max-retries=5")
                    .addDeadLetterQueue("poison-events", "max-retries=1")
                    .addEventHandler("OrderHandler", "idempotent")
                    .addEventHandler("PaymentHandler", "at-least-once")
                    .addErrorHandlingStrategy("OrderHandler")
                    .addErrorHandlingStrategy("PaymentHandler")
                    .addErrorHandlingStrategy("InventoryHandler")
                    .build();

            // Then
            assertThat(context.getDeadLetterQueues()).hasSize(2);
            assertThat(context.getErrorHandlingStrategies()).hasSize(3);
            assertThat(context.getIdempotencyPatterns()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty event-driven context")
        void shouldHandleEmptyEventDrivenContext() {
            // When
            EventDrivenContext context = EventDrivenContext.builder().build();

            // Then
            assertThat(context.getMessageBrokers()).isEmpty();
            assertThat(context.getEventHandlers()).isEmpty();
            assertThat(context.getEventStores()).isEmpty();
            assertThat(context.getSagas()).isEmpty();
            assertThat(context.getChoreography()).isEmpty();
            assertThat(context.getOrchestration()).isEmpty();
        }

        @Test
        @DisplayName("should throw when adding null message broker")
        void shouldThrowWhenAddingNullMessageBroker() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addMessageBroker(null, Map.of("key", "value"))
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Message broker cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null broker configuration")
        void shouldThrowWhenAddingNullBrokerConfiguration() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addMessageBroker("Kafka", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Broker configuration cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null dead letter queue")
        void shouldThrowWhenAddingNullDeadLetterQueue() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addDeadLetterQueue(null, "config")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Queue name cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null DLQ configuration")
        void shouldThrowWhenAddingNullDLQConfiguration() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addDeadLetterQueue("dlq", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("DLQ configuration cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null event handler")
        void shouldThrowWhenAddingNullEventHandler() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventHandler(null, "pattern")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Event handler cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null idempotency pattern to handler")
        void shouldThrowWhenAddingNullIdempotencyPattern() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventHandler("handler", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Idempotency pattern cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null event store")
        void shouldThrowWhenAddingNullEventStore() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventStore(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Event store cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null event schema")
        void shouldThrowWhenAddingNullEventSchema() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventSchema(null, "v1.0")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Schema name cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null schema version")
        void shouldThrowWhenAddingNullSchemaVersion() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventSchema("schema", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Schema version cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null saga")
        void shouldThrowWhenAddingNullSaga() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addSaga(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Saga cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null event source")
        void shouldThrowWhenAddingNullEventSource() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventSource(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Event source cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null event consumer")
        void shouldThrowWhenAddingNullEventConsumer() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addEventConsumer(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Event consumer cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null error handling strategy")
        void shouldThrowWhenAddingNullErrorHandlingStrategy() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addErrorHandlingStrategy(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Error handling strategy cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null projection")
        void shouldThrowWhenAddingNullProjection() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addProjection(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Projection cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null orchestration")
        void shouldThrowWhenAddingNullOrchestration() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addOrchestration(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Orchestration pattern cannot be null");
        }

        @Test
        @DisplayName("should throw when adding null choreography")
        void shouldThrowWhenAddingNullChoreography() {
            assertThatThrownBy(() -> EventDrivenContext.builder()
                    .addChoreography(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Choreography pattern cannot be null");
        }
    }
}
