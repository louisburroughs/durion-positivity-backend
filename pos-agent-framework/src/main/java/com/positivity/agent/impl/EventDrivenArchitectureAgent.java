package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Event-Driven Architecture Agent - Specialized expertise for event-driven patterns
 * Provides guidance on event schemas, message brokers, and asynchronous communication
 * 
 * Validates: Requirements REQ-012.1, REQ-012.2, REQ-012.3, REQ-012.4, REQ-012.5
 */
@Component
public class EventDrivenArchitectureAgent extends BaseAgent {

    public EventDrivenArchitectureAgent() {
        super(
            "event-driven-agent",
            "Event-Driven Architecture Agent", 
            "event-driven",
            Set.of("event-schemas", "kafka", "sns-sqs", "rabbitmq", "event-sourcing", 
                   "idempotency", "message-brokers", "async-communication", "event-handlers"),
            Set.of("architecture-agent", "resilience-agent"), // Depends on architectural and resilience guidance
            AgentPerformanceSpec.defaultSpec()
        );
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        String guidance = generateEventDrivenGuidance(request);
        List<String> recommendations = generateEventDrivenRecommendations(request);
        
        return AgentGuidanceResponse.success(
            request.requestId(),
            getId(),
            guidance,
            0.92, // High confidence for event-driven guidance
            recommendations,
            Duration.ofMillis(180)
        );
    }

    private String generateEventDrivenGuidance(AgentConsultationRequest request) {
        String baseGuidance = "Event-Driven Architecture Guidance for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // Event Schema Design and Versioning (REQ-012.1)
        if (query.contains("event schema") || query.contains("schema design") || query.contains("event versioning")) {
            baseGuidance += generateEventSchemaGuidance(request);
        }
        
        // Idempotent Event Handler Patterns (REQ-012.2)
        else if (query.contains("event handler") || query.contains("idempotent") || query.contains("event processing")) {
            baseGuidance += generateEventHandlerGuidance(request);
        }
        
        // Message Broker Configuration (REQ-012.3)
        else if (query.contains("kafka") || query.contains("sns") || query.contains("sqs") || 
                 query.contains("rabbitmq") || query.contains("message broker")) {
            baseGuidance += generateMessageBrokerGuidance(request);
        }
        
        // Event Sourcing Patterns (REQ-012.4)
        else if (query.contains("event sourcing") || query.contains("event store") || query.contains("replay")) {
            baseGuidance += generateEventSourcingGuidance(request);
        }
        
        // Event Failure Handling (REQ-012.5)
        else if (query.contains("dead letter") || query.contains("retry") || query.contains("circuit breaker") ||
                 query.contains("event failure") || query.contains("error handling")) {
            baseGuidance += generateEventFailureHandlingGuidance(request);
        }
        
        // General event-driven guidance
        else {
            baseGuidance += generateGeneralEventDrivenGuidance(request);
        }

        return baseGuidance;
    }

    private String generateEventSchemaGuidance(AgentConsultationRequest request) {
        return """
            Event Schema Design and Versioning Best Practices:
            
            1. Schema Evolution Strategy:
               - Use semantic versioning for event schemas (major.minor.patch)
               - Maintain backward compatibility for minor versions
               - Implement schema registry (Confluent Schema Registry or AWS Glue)
               
            2. Event Schema Structure:
               ```json
               {
                 "eventId": "uuid",
                 "eventType": "OrderCreated",
                 "eventVersion": "1.2.0",
                 "timestamp": "2024-12-16T10:30:00Z",
                 "source": "pos-order-service",
                 "data": {
                   // Event-specific payload
                 },
                 "metadata": {
                   "correlationId": "uuid",
                   "causationId": "uuid"
                 }
               }
               ```
               
            3. Backward Compatibility Rules:
               - Never remove required fields
               - Add new fields as optional with defaults
               - Use field deprecation markers for removal planning
               - Maintain multiple schema versions during transitions
               
            4. Schema Registry Integration:
               - Register all schemas before deployment
               - Validate producer/consumer compatibility
               - Implement schema evolution policies
               - Use schema fingerprinting for version detection
            """;
    }

    private String generateEventHandlerGuidance(AgentConsultationRequest request) {
        return """
            Idempotent Event Handler Implementation:
            
            1. Idempotency Patterns:
               - Use event IDs for deduplication
               - Implement idempotency keys in database
               - Check processing status before handling
               
            2. Spring Boot Event Handler Example:
               ```java
               @EventListener
               @Transactional
               public void handleOrderCreated(OrderCreatedEvent event) {
                   // Check if already processed
                   if (eventProcessingRepository.isProcessed(event.getEventId())) {
                       log.info("Event {} already processed, skipping", event.getEventId());
                       return;
                   }
                   
                   try {
                       // Process event
                       processOrderCreation(event);
                       
                       // Mark as processed
                       eventProcessingRepository.markProcessed(event.getEventId());
                   } catch (Exception e) {
                       log.error("Failed to process event {}", event.getEventId(), e);
                       throw e; // Trigger retry mechanism
                   }
               }
               ```
               
            3. Error Handling Patterns:
               - Implement exponential backoff for retries
               - Use dead letter queues for failed events
               - Log processing attempts with correlation IDs
               - Implement circuit breakers for downstream dependencies
               
            4. Transactional Outbox Pattern:
               - Store events in same transaction as business data
               - Use separate process to publish events
               - Ensures consistency between state changes and events
            """;
    }

    private String generateMessageBrokerGuidance(AgentConsultationRequest request) {
        String query = request.query().toLowerCase();
        
        if (query.contains("kafka")) {
            return generateKafkaGuidance();
        } else if (query.contains("sns") || query.contains("sqs")) {
            return generateSNSSQSGuidance();
        } else if (query.contains("rabbitmq")) {
            return generateRabbitMQGuidance();
        } else {
            return generateGeneralBrokerGuidance();
        }
    }

    private String generateKafkaGuidance() {
        return """
            Apache Kafka Configuration for Spring Boot:
            
            1. Producer Configuration:
               ```yaml
               spring:
                 kafka:
                   producer:
                     bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
                     key-serializer: org.apache.kafka.common.serialization.StringSerializer
                     value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
                     acks: all
                     retries: 3
                     batch-size: 16384
                     linger-ms: 5
                     enable-idempotence: true
               ```
               
            2. Consumer Configuration:
               ```yaml
               spring:
                 kafka:
                   consumer:
                     bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
                     group-id: ${spring.application.name}
                     key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
                     value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
                     auto-offset-reset: earliest
                     enable-auto-commit: false
                     max-poll-records: 100
               ```
               
            3. Topic Configuration:
               - Use partitioning strategy based on business keys
               - Set appropriate replication factor (min 3 for production)
               - Configure retention policies based on data requirements
               - Use compacted topics for entity state events
               
            4. Spring Kafka Integration:
               ```java
               @KafkaListener(topics = "order-events", groupId = "inventory-service")
               public void handleOrderEvent(OrderEvent event, Acknowledgment ack) {
                   try {
                       processOrderEvent(event);
                       ack.acknowledge();
                   } catch (Exception e) {
                       // Handle error - don't acknowledge
                       log.error("Failed to process order event", e);
                   }
               }
               ```
            """;
    }

    private String generateSNSSQSGuidance() {
        return """
            AWS SNS/SQS Configuration for Event-Driven Architecture:
            
            1. SNS Topic Configuration:
               ```yaml
               aws:
                 sns:
                   topics:
                     order-events: arn:aws:sns:us-east-1:123456789012:order-events
                     inventory-events: arn:aws:sns:us-east-1:123456789012:inventory-events
               ```
               
            2. SQS Queue Configuration:
               ```yaml
               aws:
                 sqs:
                   queues:
                     order-processing: 
                       url: https://sqs.us-east-1.amazonaws.com/123456789012/order-processing
                       visibility-timeout: 300
                       message-retention-period: 1209600
                       dead-letter-queue:
                         url: https://sqs.us-east-1.amazonaws.com/123456789012/order-processing-dlq
                         max-receive-count: 3
               ```
               
            3. Spring Cloud AWS Integration:
               ```java
               @SqsListener("order-processing")
               public void handleOrderMessage(OrderMessage message, Acknowledgment ack) {
                   try {
                       processOrder(message);
                       ack.acknowledge();
                   } catch (RetryableException e) {
                       // Don't acknowledge - message will be retried
                       log.warn("Retryable error processing order", e);
                   } catch (Exception e) {
                       // Acknowledge to prevent infinite retries
                       log.error("Non-retryable error processing order", e);
                       ack.acknowledge();
                   }
               }
               ```
               
            4. Fan-out Pattern with SNS:
               - Publish events to SNS topics
               - Subscribe multiple SQS queues to topics
               - Each service gets its own queue for decoupling
               - Use message filtering for selective consumption
            """;
    }

    private String generateRabbitMQGuidance() {
        return """
            RabbitMQ Configuration for Spring Boot:
            
            1. Connection Configuration:
               ```yaml
               spring:
                 rabbitmq:
                   host: ${RABBITMQ_HOST:localhost}
                   port: ${RABBITMQ_PORT:5672}
                   username: ${RABBITMQ_USER:guest}
                   password: ${RABBITMQ_PASSWORD:guest}
                   virtual-host: /
                   connection-timeout: 30000
                   publisher-confirms: true
                   publisher-returns: true
               ```
               
            2. Exchange and Queue Configuration:
               ```java
               @Configuration
               public class RabbitConfig {
                   
                   @Bean
                   public TopicExchange orderExchange() {
                       return new TopicExchange("order.exchange", true, false);
                   }
                   
                   @Bean
                   public Queue orderProcessingQueue() {
                       return QueueBuilder.durable("order.processing")
                           .withArgument("x-dead-letter-exchange", "order.dlx")
                           .build();
                   }
                   
                   @Bean
                   public Binding orderBinding() {
                       return BindingBuilder.bind(orderProcessingQueue())
                           .to(orderExchange())
                           .with("order.created");
                   }
               }
               ```
               
            3. Message Listener:
               ```java
               @RabbitListener(queues = "order.processing")
               public void handleOrderEvent(OrderEvent event, Channel channel, 
                                          @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
                   try {
                       processOrderEvent(event);
                       channel.basicAck(tag, false);
                   } catch (Exception e) {
                       log.error("Failed to process order event", e);
                       channel.basicNack(tag, false, false); // Send to DLQ
                   }
               }
               ```
            """;
    }

    private String generateGeneralBrokerGuidance() {
        return """
            Message Broker Selection and Configuration:
            
            1. Broker Comparison:
               - Kafka: High throughput, event streaming, log-based storage
               - RabbitMQ: Feature-rich, flexible routing, AMQP protocol
               - AWS SNS/SQS: Managed service, serverless, AWS integration
               
            2. Common Configuration Patterns:
               - Enable message persistence for durability
               - Configure dead letter queues for error handling
               - Set appropriate timeouts and retry policies
               - Use message acknowledgments for reliability
               
            3. Monitoring and Observability:
               - Track message throughput and latency
               - Monitor queue depths and consumer lag
               - Set up alerts for dead letter queue activity
               - Implement distributed tracing for message flows
            """;
    }

    private String generateEventSourcingGuidance(AgentConsultationRequest request) {
        return """
            Event Sourcing Implementation Patterns:
            
            1. Event Store Design:
               ```java
               @Entity
               public class EventStore {
                   @Id
                   private String eventId;
                   private String aggregateId;
                   private String eventType;
                   private String eventData;
                   private Long version;
                   private Instant timestamp;
                   
                   // Getters and setters
               }
               ```
               
            2. Aggregate Root Pattern:
               ```java
               public abstract class AggregateRoot {
                   private List<DomainEvent> uncommittedEvents = new ArrayList<>();
                   
                   protected void addEvent(DomainEvent event) {
                       uncommittedEvents.add(event);
                   }
                   
                   public List<DomainEvent> getUncommittedEvents() {
                       return List.copyOf(uncommittedEvents);
                   }
                   
                   public void markEventsAsCommitted() {
                       uncommittedEvents.clear();
                   }
               }
               ```
               
            3. Event Replay Mechanism:
               ```java
               public T replayEvents(String aggregateId, Class<T> aggregateType) {
                   List<DomainEvent> events = eventStore.getEvents(aggregateId);
                   T aggregate = createEmptyAggregate(aggregateType);
                   
                   for (DomainEvent event : events) {
                       aggregate.apply(event);
                   }
                   
                   return aggregate;
               }
               ```
               
            4. Snapshot Strategy:
               - Create snapshots at regular intervals (every 100 events)
               - Store snapshot version to optimize replay
               - Implement snapshot serialization/deserialization
               - Use snapshots as starting point for replay
            """;
    }

    private String generateEventFailureHandlingGuidance(AgentConsultationRequest request) {
        return """
            Event Failure Handling and Resilience Patterns:
            
            1. Dead Letter Queue Configuration:
               ```java
               @Bean
               public Queue deadLetterQueue() {
                   return QueueBuilder.durable("events.dlq")
                       .withArgument("x-message-ttl", 86400000) // 24 hours
                       .build();
               }
               ```
               
            2. Retry Mechanism with Exponential Backoff:
               ```java
               @Retryable(value = {RetryableException.class}, 
                         maxAttempts = 3, 
                         backoff = @Backoff(delay = 1000, multiplier = 2))
               public void processEvent(DomainEvent event) {
                   // Event processing logic
               }
               
               @Recover
               public void recover(RetryableException ex, DomainEvent event) {
                   // Send to dead letter queue or alert
                   deadLetterService.sendToDeadLetter(event, ex);
               }
               ```
               
            3. Circuit Breaker for Event Processing:
               ```java
               @CircuitBreaker(name = "event-processor", fallbackMethod = "fallbackEventProcessing")
               public void processEvent(DomainEvent event) {
                   // Process event with external dependencies
               }
               
               public void fallbackEventProcessing(DomainEvent event, Exception ex) {
                   // Fallback logic - queue for later processing
                   eventRetryService.scheduleRetry(event);
               }
               ```
               
            4. Event Processing Monitoring:
               - Track processing success/failure rates
               - Monitor dead letter queue depths
               - Set up alerts for processing delays
               - Implement event processing dashboards
            """;
    }

    private String generateGeneralEventDrivenGuidance(AgentConsultationRequest request) {
        return """
            General Event-Driven Architecture Principles:
            
            1. Event Design Principles:
               - Events should be immutable and represent facts
               - Use past tense naming (OrderCreated, not CreateOrder)
               - Include sufficient context for processing
               - Avoid coupling events to specific consumers
               
            2. Microservices Integration:
               - Use events for eventual consistency
               - Implement saga patterns for distributed transactions
               - Design for idempotency and duplicate handling
               - Maintain service autonomy through events
               
            3. Performance Considerations:
               - Batch event processing where appropriate
               - Use event streaming for high-throughput scenarios
               - Implement proper partitioning strategies
               - Monitor and optimize consumer lag
               
            4. Security and Compliance:
               - Encrypt sensitive data in events
               - Implement event audit trails
               - Use secure transport (TLS) for event transmission
               - Consider data retention and privacy requirements
            """;
    }

    private List<String> generateEventDrivenRecommendations(AgentConsultationRequest request) {
        String query = request.query().toLowerCase();
        
        if (query.contains("event schema") || query.contains("schema")) {
            return List.of(
                "Implement schema registry for version management",
                "Use semantic versioning for event schemas",
                "Design events with backward compatibility in mind",
                "Include correlation and causation IDs in events"
            );
        } else if (query.contains("kafka")) {
            return List.of(
                "Enable idempotent producers for exactly-once semantics",
                "Use appropriate partitioning strategy for scalability",
                "Configure proper retention policies for topics",
                "Implement consumer group management for load balancing"
            );
        } else if (query.contains("event handler") || query.contains("processing")) {
            return List.of(
                "Implement idempotency checks in event handlers",
                "Use transactional outbox pattern for consistency",
                "Design for graceful degradation and error handling",
                "Implement proper logging and monitoring for event processing"
            );
        } else {
            return List.of(
                "Choose appropriate message broker based on requirements",
                "Design events as immutable facts, not commands",
                "Implement proper error handling and retry mechanisms",
                "Use event sourcing for audit trails and temporal queries",
                "Design for eventual consistency in distributed systems"
            );
        }
    }
}