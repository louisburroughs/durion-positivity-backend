# Event Outbox Pattern Implementation

## Overview

This module implements the **Transactional Outbox Pattern** to ensure **at-least-once event delivery** with ACID guarantees. Events are persisted to the database in the same transaction as business operations, then asynchronously published by a background processor.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Business Transaction                       │
│  ┌──────────────────┐         ┌──────────────────┐         │
│  │  APPayment Save  │────────▶│  Event to Outbox │         │
│  └──────────────────┘         └──────────────────┘         │
│         (ACID commit of both operations)                    │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │      OutboxProcessor (scheduled)      │
        │   • Poll pending events (every 5s)    │
        │   • Publish to ApplicationEventPublisher│
        │   • Mark as PUBLISHED                 │
        │   • Retry failed events (30s delay)   │
        └───────────────────────────────────────┘
```

## Components

### 1. EventOutbox Entity
**File**: `internal/entity/EventOutbox.java`

Persistent record of events awaiting publication.

**Key Fields**:
- `eventId`: Unique event identifier (idempotency key)
- `aggregateType`: Business entity type (e.g., "APPayment")
- `aggregateId`: Business entity ID
- `eventType`: Fully qualified event class name
- `payload`: JSON-serialized event data
- `status`: PENDING | PUBLISHED | FAILED
- `retryCount`: Number of publication attempts

**Lifecycle**:
```
PENDING → (published successfully) → PUBLISHED
PENDING → (failed N times, N < max) → PENDING (retry)
PENDING → (failed N times, N >= max) → FAILED
```

### 2. EventOutboxRepository
**File**: `internal/repository/EventOutboxRepository.java`

JPA repository for outbox persistence operations.

**Key Methods**:
- `findByStatusOrderByCreatedAtAsc`: Get pending events (FIFO)
- `findPendingForRetry`: Get events eligible for retry (respects retry delay)
- `deleteOldPublishedEvents`: Cleanup old published events

### 3. OutboxService
**File**: `service/OutboxService.java`

Service layer for outbox operations.

**Key Methods**:
- `saveToOutbox(...)`: Persist event to outbox (MUST be called in active transaction)
- `markAsPublished(...)`: Mark event as successfully published (REQUIRES_NEW transaction)
- `markAsFailed(...)`: Record failed attempt and increment retry count
- `cleanupOldEvents(...)`: Delete old published events (archival)

### 4. OutboxProcessor
**File**: `service/OutboxProcessor.java`

Background scheduled task that polls and publishes events.

**Configuration**:
- Poll interval: Every 5 seconds (configurable via `@Scheduled`)
- Batch size: 10 events per poll
- Retry delay: 30 seconds between attempts
- Max retries: 5 attempts before marking as FAILED
- Cleanup schedule: Daily at 2 AM (deletes events > 30 days old)

## Usage

### Step 1: Create Your Event DTO
Define a serializable event class (already exists for AP Payments):

```java
@Data
@Builder
public class APPaymentGLPostingEvent {
    private UUID eventId;
    private UUID paymentId;
    private String paymentRef;
    // ... other fields
}
```

### Step 2: Inject OutboxService
Add `OutboxService` to your service class:

```java
@Service
@RequiredArgsConstructor
public class APPaymentServiceImpl {
    private final OutboxService outboxService;
    // ...
}
```

### Step 3: Save Event to Outbox (Within Transaction)
Replace direct event publishing with outbox persistence:

```java
@Transactional
public APPaymentResponse executePayment(ExecuteAPPaymentRequest request) {
    // ... business logic
    payment = paymentRepository.save(payment);
    
    // Build event
    APPaymentGLPostingEvent event = APPaymentGLPostingEvent.builder()
        .eventId(UUID.randomUUID())
        .paymentId(payment.getPaymentId())
        // ...
        .build();
    
    // Save to outbox (atomic with payment save)
    outboxService.saveToOutbox(
        event.getEventId(),
        "APPayment",                          // aggregate type
        payment.getPaymentId(),               // aggregate ID
        event.getClass().getName(),           // event type
        event                                 // event object
    );
    
    return toResponse(payment);
}
```

### Step 4: Register Event Type in OutboxProcessor
If adding a new event type, update the `deserializeEvent` method:

```java
private Object deserializeEvent(EventOutbox outbox) throws Exception {
    String eventType = outbox.getEventType();
    
    Class<?> eventClass = switch (eventType) {
        case "com.positivity.accounting.internal.dto.APPaymentGLPostingEvent" 
            -> APPaymentGLPostingEvent.class;
        case "com.positivity.accounting.internal.dto.YourNewEvent" 
            -> YourNewEvent.class;  // Add this
        default -> throw new IllegalArgumentException("Unsupported event type: " + eventType);
    };
    
    return objectMapper.readValue(outbox.getPayload(), eventClass);
}
```

## Database Schema

**Table**: `event_outbox`

```sql
CREATE TABLE event_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    last_attempt_at TIMESTAMP
);

-- Indexes for efficient polling and querying
CREATE INDEX idx_outbox_status_created ON event_outbox(status, created_at);
CREATE INDEX idx_outbox_aggregate ON event_outbox(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_event_type ON event_outbox(event_type);
```

**Migration**: `db/migration/V2__create_event_outbox_table.sql`

## Guarantees

### ✅ What This Provides
- **Atomicity**: Event persistence is atomic with business operation (same transaction)
- **At-least-once delivery**: Events are retried until successfully published
- **Ordering**: Events are processed in creation order (FIFO per aggregate)
- **Durability**: Events survive application crashes (persisted to database)
- **Idempotency**: Each event has unique `eventId` for duplicate detection

### ⚠️ What This Does NOT Provide
- **Exactly-once delivery**: Consumers must be idempotent (use `eventId` for deduplication)
- **Ordering across aggregates**: Only FIFO ordering within same aggregate type
- **Real-time delivery**: Typical latency is 5-35 seconds (poll interval + retry delay)

## Monitoring

### Key Metrics to Track
1. **Pending events count**: `SELECT COUNT(*) FROM event_outbox WHERE status = 'PENDING'`
2. **Failed events count**: `SELECT COUNT(*) FROM event_outbox WHERE status = 'FAILED'`
3. **Oldest pending event**: `SELECT MIN(created_at) FROM event_outbox WHERE status = 'PENDING'`
4. **Retry rates**: `SELECT AVG(retry_count) FROM event_outbox WHERE status = 'PUBLISHED'`

### Query Examples

```sql
-- Find stuck events (pending for > 10 minutes)
SELECT * FROM event_outbox 
WHERE status = 'PENDING' 
  AND created_at < NOW() - INTERVAL '10 minutes'
ORDER BY created_at;

-- Find events with high retry counts
SELECT * FROM event_outbox 
WHERE retry_count >= 3 
ORDER BY retry_count DESC, created_at;

-- Count events by status
SELECT status, COUNT(*) 
FROM event_outbox 
GROUP BY status;

-- Failed events with error messages
SELECT event_id, aggregate_type, aggregate_id, retry_count, last_error
FROM event_outbox 
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

## Configuration

### Tuning Parameters

Edit `OutboxProcessor.java` to adjust:

```java
// Batch size (events per poll)
private static final int BATCH_SIZE = 10;  // Increase for higher throughput

// Max retries before marking as FAILED
private static final int MAX_RETRIES = 5;  // Increase for more resilience

// Retry delay (time between attempts)
private static final Duration RETRY_DELAY = Duration.ofSeconds(30);  // Adjust based on failure patterns

// Poll interval (in @Scheduled annotation)
@Scheduled(fixedRate = 5000)  // Decrease for lower latency, increase to reduce DB load
```

### Cleanup Schedule

Old published events are automatically deleted after 30 days. To adjust:

```java
@Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
public void cleanupOldEvents() {
    Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));  // Change retention period
    // ...
}
```

## Troubleshooting

### Events Not Being Published
1. Check OutboxProcessor is running: Look for log entry "Processing X pending outbox events"
2. Check for errors in logs: `grep "Error processing outbox" application.log`
3. Query pending events: `SELECT * FROM event_outbox WHERE status = 'PENDING'`
4. Check retry delay: Events may be waiting for retry delay (30s) to elapse

### High Failure Rate
1. Check `last_error` column: `SELECT last_error FROM event_outbox WHERE status = 'FAILED' LIMIT 10`
2. Common causes:
   - Deserialization errors (event schema changed)
   - Event consumer is down or throwing exceptions
   - Network issues (if publishing to external message broker)
3. Fix root cause, then manually reset failed events:
   ```sql
   UPDATE event_outbox 
   SET status = 'PENDING', retry_count = 0, last_error = NULL 
   WHERE status = 'FAILED' AND aggregate_id = '<specific_id>';
   ```

### Performance Issues
1. **High pending count**: Increase `BATCH_SIZE` or decrease poll interval
2. **Database load**: Increase poll interval or add database connection pool
3. **Memory pressure**: Decrease `BATCH_SIZE` or add pagination

## Best Practices

### DO
✅ Use outbox for all critical events that require guaranteed delivery  
✅ Make event consumers idempotent (check for duplicate `eventId`)  
✅ Include sufficient context in event payload for troubleshooting  
✅ Monitor pending/failed event counts  
✅ Test retry logic with intentional failures  

### DON'T
❌ Don't call `saveToOutbox` outside of an active transaction  
❌ Don't modify payload after saving to outbox  
❌ Don't delete failed events without investigating root cause  
❌ Don't use for real-time requirements (< 5 second latency)  
❌ Don't forget to register new event types in `OutboxProcessor`  

## Testing

### Unit Test Example

```java
@Test
void shouldSaveEventToOutbox() {
    // Arrange
    APPaymentGLPostingEvent event = APPaymentGLPostingEvent.builder()
        .eventId(UUID.randomUUID())
        .paymentId(UUID.randomUUID())
        .build();
    
    // Act
    EventOutbox outbox = outboxService.saveToOutbox(
        event.getEventId(),
        "APPayment",
        event.getPaymentId(),
        event.getClass().getName(),
        event
    );
    
    // Assert
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(outbox.getRetryCount()).isEqualTo(0);
}
```

### Integration Test Example

```java
@SpringBootTest
@Transactional
class OutboxIntegrationTest {
    
    @Autowired
    private APPaymentService paymentService;
    
    @Autowired
    private EventOutboxRepository outboxRepository;
    
    @Test
    void executePayment_shouldPersistEventToOutbox() {
        // Arrange
        ExecuteAPPaymentRequest request = createValidRequest();
        
        // Act
        APPaymentResponse response = paymentService.executePayment(request, "testUser");
        
        // Assert
        List<EventOutbox> outboxEvents = outboxRepository.findByStatusOrderByCreatedAtAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 10));
        
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getAggregateId()).isEqualTo(response.getPaymentId());
    }
}
```

## References

- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Implementing the Outbox Pattern](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/)
- [Event-Driven Architecture](https://martinfowler.com/articles/201701-event-driven.html)

## Related Files

- `EventOutbox.java` - Entity definition
- `EventOutboxRepository.java` - Data access layer
- `OutboxService.java` - Business logic layer
- `OutboxProcessor.java` - Background processor
- `APPaymentServiceImpl.java` - Usage example
- `V2__create_event_outbox_table.sql` - Database migration

---

**Last Updated**: 2026-02-12  
**Status**: ✅ Production Ready  
**Maintainer**: Platform Team
