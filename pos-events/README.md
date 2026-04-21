# pos-events — Shared Library

A **Spring Boot auto-configuration library** that provides annotation-driven event emission for decoupled microservice communication across the Positivity POS platform.

## Purpose

pos-events enables microservices to emit domain events asynchronously without introducing hard dependencies on event consumers. Services annotate business logic methods with `@EmitEvent` to automatically track important state changes and business transactions.

## ⚠️ Critical Design Constraint

**pos-events is a pure helper/library module. It MUST NOT:**
- ❌ Have a database connection or data source
- ❌ Register with service discovery (Eureka)
- ❌ Expose REST API endpoints or gateway routes
- ❌ Have external service dependencies

**This module provides only:**
- ✅ Annotation-driven event emission (`@EmitEvent`)
- ✅ Shared profile-aware application time (`Clock`, `ScaledClock`, `TimeSource`)
- ✅ Auto-configuration for Spring Boot
- ✅ Event publishing via Spring's `ApplicationEventPublisher`

**Violation of these constraints will:**
1. Break the module's reusability across services
2. Create circular dependencies
3. Prevent independent deployment
4. Violate microservice architecture principles

pos-events is a **dependency consumed by other modules**, not a service itself.

## Public API

### `@EmitEvent`
Marker annotation placed on service methods to automatically emit events when the method executes successfully.

```java
@Service
public class OrderService {
    
    @EmitEvent
    public Order createOrder(OrderRequest request) {
        Order order = new Order(request);
        orderRepository.save(order);
        return order;
    }
}
```

**Automatic behavior:**
- Event is emitted after method returns successfully
- Event payload includes method name, execution time, and result
- Event name: `{service}.{method}` (e.g., `order-service.createOrder`)
- Failed methods do NOT emit events (only successful executions)

### `EventEmitted`
Record (data class) containing the event payload.

```java
public record EventEmitted(
    String eventName,           // e.g., "order-service.createOrder"
    Object result,               // method return value
    long executionTimeMs        // method execution duration
) {}
```

### `EmitEventProxyFactory`
Optional factory for manual event emission in non-method-level contexts.

```java
@Component
public class SomeComponent {
    private final EmitEventProxyFactory factory;
    
    public SomeComponent(EmitEventProxyFactory factory) {
        this.factory = factory;
    }
    
    public void doSomething() {
        // manual event emission
        factory.emit(new EventEmitted("custom-event", data, 100L));
    }
}
```

### Shared Application Time
`pos-events` also owns the shared backend `Clock` auto-configuration. Consumer
modules should inject `Clock` for service/config code instead of declaring local
default `Clock.systemUTC()` beans.

Default behavior:

```java
@Service
public class SomeService {
    private final Clock clock;

    public SomeService(Clock clock) {
        this.clock = clock;
    }
}
```

When the Spring profile `accelerated` is active, the shared `Clock` bean is a
`ScaledClock`. Otherwise, the shared bean is `Clock.systemUTC()`.

For entity lifecycle methods or other non-injectable code, use
`TimeSource.instant()` or `TimeSource.localDateTime()` so timestamps still follow
the active application clock.

## Usage in Consumer Modules

### 1. Add pos-events Dependency

The dependency is already centralized in the root `pos-dependencies` BOM. Just reference it:

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-events</artifactId>
</dependency>
```

### 2. Enable Auto-Configuration

**Automatic:** No manual configuration needed. The library's auto-configuration activates on classpath inclusion.

**Verify activation** (optional, in application logs):
```
Registering auto-configuration: PosEventsApplication
Registering bean: emitEventAspect
Registering bean: emitEventProxyFactory
```

### 3. Configure Accelerated Time

Activate accelerated time with the Spring profile:

```bash
SPRING_PROFILES_ACTIVE=dev,accelerated
```

Optional properties:

```properties
pos.time.accelerated.scale=1000.0
pos.time.accelerated.zone=UTC
```

`scale` must be a finite positive number. `zone` must be a valid Java
`ZoneId`, such as `UTC` or `America/New_York`.

### 4. Annotate Business Methods

```java
@Service
public class WorkorderService {
    
    @EmitEvent
    public Workorder createWorkorder(CreateWorkorderRequest request) {
        // business logic
        return workorder;
    }
    
    @EmitEvent
    public Workorder updateWorkorderStatus(Long id, WorkorderStatus status) {
        // business logic
        return updatedWorkorder;
    }
}
```

### 5. Listen to Events (Optional)

If another module wants to consume emitted events:

```java
@Component
public class WorkorderAuditListener {
    
    @EventListener
    public void onWorkorderCreated(EventEmitted event) {
        if (event.eventName().contains("createWorkorder")) {
            // log to audit trail, send notification, etc.
        }
    }
}
```

## Consuming Modules

The following modules currently use pos-events:

| Module | Usage | Purpose |
|--------|-------|---------|
| pos-accounting | `@EmitEvent` | Track journal entries and financial transactions |
| pos-workorder | `@EmitEvent` | Track workorder lifecycle events |
| pos-catalog | `@EmitEvent` | Track catalog updates and product changes |
| pos-vehicle-fitment | `@EventListener` | Audit fitment compatibility checks |
| pos-image | `Clock` | Shared application/auditing time |
| pos-vehicle-reference-carapi | `Clock` | Shared application/auditing time |
| pos-vehicle-reference-nhtsa | `Clock` | Shared application/auditing time |
| pos-event-receiver | `@EventListener` | Centralized event log collection |
| pos-archunit | Test scope | Validate event emission architecture |

## Implementation Details

### How It Works

1. **AspectJ Aspect Interception**: `EmitEventAspect` uses Spring AOP to intercept methods annotated with `@EmitEvent`
2. **Post-Execution Emission**: After successful method execution, an `EventEmitted` record is created and published
3. **Spring Event Mechanism**: Events are published via `ApplicationEventPublisher` (standard Spring mechanism)
4. **Async Consumption**: Listeners consume events asynchronously via `@EventListener` (non-blocking)
5. **Shared Time Auto-Configuration**: `TimeConfig` creates exactly one
   application `Clock`, using `ScaledClock` only under the `accelerated` profile.

### Package Structure

```
com.positivity.events/
├── EmitEvent.java              (public: annotation)
├── EventEmitted.java           (public: event record)
├── PosEventsApplication.java   (public: auto-configuration)
├── TimeConfig.java             (public: Clock auto-configuration)
├── EmitEventAspect.java        (internal: aspect implementation)
├── EmitEventProxy.java         (internal: proxy logic)
└── EmitEventProxyFactory.java  (public: factory bean)

com.positivity.time/
├── ScaledClock.java            (public: accelerated Clock implementation)
├── MetricTime.java             (public: metric time helper)
└── TimeSource.java             (public: static bridge to active Clock)
```

**Public API** (consumed by other modules):
- `EmitEvent` annotation
- `EventEmitted` record
- `EmitEventProxyFactory` bean
- `PosEventsApplication` (auto-configuration, transparent to consumers)
- `TimeConfig` (auto-configuration, transparent to consumers)
- `ScaledClock`, `MetricTime`, and `TimeSource`

**Internal Implementation** (implementation details):
- `EmitEventAspect` (aspect interceptor)
- `EmitEventProxy` (proxy utility)

## Dependency Management

All versions are centralized in the root `pom.xml`:

| Dependency | Version | Purpose |
|---|---|---|
| Spring Framework (spring-context) | 6.x (from parent) | Core annotation support |
| Spring Boot (spring-boot-starter-aop) | 3.4.2 (from parent) | AOP framework |
| Spring Boot (spring-boot-autoconfigure) | 3.4.2 (from parent) | Auto-configuration machinery |
| Lombok | 1.18.32 (centralized property) | Annotation processing |
| SLF4J | 2.0.13 (centralized property) | Logging API |

**No transitive dependencies** on logging implementations, web frameworks, or databases. Consumers provide their own logging via `spring-boot-starter-logging`.

## Guidelines for Contributors

### When to Use `@EmitEvent`

✅ **DO use** for:
- Service method that performs significant business operations
- State changes that should be tracked or audited
- Events that other modules might need to know about
- Post-transaction operations (order created, payment processed, etc.)

❌ **DON'T use** for:
- Read-only queries or lookups
- Internal helper methods
- Methods that frequently throw exceptions (events only emit on success)
- High-frequency operations (events add minimal overhead but scale with frequency)

### Testing Event Emission

```java
@SpringBootTest
public class WorkorderServiceTest {
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired
    private WorkorderService service;
    
    @Test
    void testOrderCreationEmitsEvent() {
        // Capture published events
        var eventCaptor = new TestEventCaptor();
        eventPublisher.addListener(eventCaptor);
        
        // Execute business logic
        Workorder created = service.createWorkorder(request);
        
        // Verify event was emitted
        assertThat(eventCaptor.getCapturedEvents())
            .anyMatch(e -> e.eventName().contains("createWorkorder"));
    }
}
```

## Future Enhancements

- **Event Schema Registry**: Centralize event definitions for cross-service contracts
- **Event Replay**: Persist and replay events for rebuilding state
- **Event Filtering**: Configure which events to emit per service
- **Metrics Integration**: Track event emission counts and latencies
- **Dead Letter Queue**: Handle events that fail to process

## See Also

- [pos-dependencies BOM](../pos-dependencies/README.md) — Internal artifact versions
- [AGENTS.md](../AGENTS.md) — Development guidelines
- Architecture ADR: Event-Driven Communication
