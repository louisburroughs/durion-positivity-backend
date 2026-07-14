# AGENTS.md — durion-positivity-backend

## Project Overview

POS backend microservice suite for Durion. Multi-module Maven project containing gateway + `pos-*` services (Spring Boot 4.0.x, Java 25).

## Quick Prerequisites

- Java 25+ (Use [SDKMAN!](https://sdkman.io/) - the project includes `.sdkmanrc` for automatic version switching)
- Maven (use `./mvnw` wrapper)
- Docker for local test stacks

**Note**: This project uses SDKMAN! for Java version management. When you `cd` into the project directory, SDKMAN! will automatically switch to Java 25.0.2-tem if you have it configured with `sdk_auto_env=true` in `~/.sdkman/etc/config`.

## Setup & Build

```bash
cd durion-positivity-backend
./mvnw -pl pos-api-gateway -am clean package  # build gateway + deps
# Build a single service (example: pos-order)
./mvnw -pl pos-order -am clean package
```

## Run Locally

Run a single service:

```bash
cd durion-positivity-backend/pos-order
./mvnw spring-boot:run
# or
java -jar target/pos-order-*.jar
```

## Testing & Linting

```bash
# Run all backend tests
cd durion-positivity-backend
./mvnw -DskipTests=false clean test
# Module-only tests
./mvnw -pl pos-order -am test
```

## Module Conventions & Intermodule Communication

Treat each `pos-*` directory as a standard Spring Boot service using existing module patterns:

- `service/` – business logic orchestration (public API for cross-module calls) Interfaces only
- `internal/controller/` – REST endpoints (keep controllers thin)
- `internal/repository/` – Spring Data JPA data access
- `internal/entity/` – JPA entities and domain types
- `internal/config/` – Spring configuration (security, DB, messaging)
- `internal/dto/` – Data transfer objects
- `internal/domain/` – Domain models
- `internal/enums/` – Enumerations

### ⚠️ MANDATORY: Internal Package Structure

**All code MUST reside in `com.positivity.{domain}.internal` packages EXCEPT service layer.** This is strictly enforced:

- **ONLY `service/` packages** (e.g., `com.positivity.accounting.service`) are exposed as Interfaces to be the public API for other modules
- **The `@SpringBootApplication` class** (e.g., `PosAccountingApplication.java`) MUST remain in the root `com.positivity.{domain}` package for proper component scanning
- **ALL other packages MUST be under `internal/`**: `internal/controller`, `internal/repository`, `internal/entity`, `internal/dto`, `internal/config`, `internal/domain`, `internal/enums`, etc.
- **Controllers, repositories, entities, DTOs, configs** are implementation details and MUST NOT be accessed directly by other modules
- **Cross-module access** happens via REST APIs through the API gateway or via message-based events
- This encapsulation prevents tight coupling and ensures modules remain independently deployable and maintainable

**Package structure example:**

```ascii
com.positivity.accounting/
├── PosAccountingApplication.java  ← Spring Boot main class (root level)
├── service/                       ← PUBLIC API (exposed to other modules)
│   ├── JournalEntryService.java
│   └── EventIngestionService.java
└── internal/                      ← PRIVATE (module internals)
    ├── controller/
    ├── repository/
    ├── entity/
    ├── dto/
    ├── config/
    ├── domain/
    └── enums/
```

### ⚠️ MANDATORY: Architecture Testing with ArchUnit

**Architecture boundaries are enforced with ArchUnit tests.** This is non-negotiable:

- **Each module MUST have ArchUnit tests** in `src/test/java/{package}/ArchitectureTest.java`
- **Tests verify internal package encapsulation** - internal packages should not be accessed from other modules
- **Tests verify layering** - controllers must not directly access repositories or entities
- **Tests verify service layer exposure** - only service packages are public APIs
- **Architecture tests run automatically** as part of Maven test phase
- Reference the `$WORKSPACE/durion-positivity-backend/pos-archunit/ArchitectureTests.java` for cross-module validation patterns
- Violating architecture rules will fail builds and prevent deployment

**Inter-service Communication Patterns:**

- **REST APIs** - Use for synchronous cross-service calls via API gateway
- **Events/Messages** - Use for asynchronous cross-service communication via message broker
- **Shared DTOs** - Service interfaces may share DTO classes for API contracts
- **NO direct database access** across service boundaries
- **NO direct repository calls** from external services

Example ArchUnit test pattern:

```java
@ArchTest
static final ArchRule controllers_should_not_access_repositories_directly =
    noClasses()
        .that().resideInAPackage("..internal.controller..")
        .should().dependOnClassesThat().resideInAPackage("..internal.repository..")
        .because("controllers must go through service layer");
```

## Events & Cross-Cutting Concerns

### ⚠️ MANDATORY: pos-events Annotations for API Event Logging

**All API events MUST be logged using `pos-events` annotations.** This is required for audit trails, observability, and compliance:

#### @EmitEvent Annotation Usage

- **Use `@EmitEvent` annotations** on all REST controller methods that perform state changes (POST, PUT, DELETE) or significant read operations.
- **Annotation format**: `@EmitEvent(id = "{MODULE}_{RESOURCE}_{ACTION}", apiVersion = "1")`
- **Event ID naming convention**: `{MODULE}_{RESOURCE}_{ACTION}` (e.g., `ORDER_PRICE_OVERRIDE_CREATE`, `WORKORDER_ESTIMATE_APPROVE`)

```java
import com.positivity.events.EmitEvent;

@PostMapping
@EmitEvent(id = "ORDER_PRICE_OVERRIDE_CREATE", apiVersion = "1")
public ResponseEntity<PriceOverride> createPriceOverride(@RequestBody PriceOverrideRequest request) {
    // ...
}
```

#### Event Type Registry Pattern

Each module MUST define an event type registry class with all event types and their performance thresholds:

```java
package com.positivity.{module}.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

public final class {Module}EventTypes {
    private {Module}EventTypes() {}

    public static List<EventTypeRegistration> all() {
        return List.of(
            // Use threshold presets based on operation type:
            EventTypeRegistration.fastRead("MODULE_RESOURCE_LIST", "List resources").build(),      // p50=50ms, p95=200ms, p99=500ms
            EventTypeRegistration.search("MODULE_RESOURCE_SEARCH", "Search resources").build(),   // p50=100ms, p95=500ms, p99=1s
            EventTypeRegistration.write("MODULE_RESOURCE_CREATE", "Create a resource").build(),   // p50=200ms, p95=1s, p99=3s
            EventTypeRegistration.approval("MODULE_RESOURCE_APPROVE", "Approve a resource").build() // p50=500ms, p95=2s, p99=5s
        );
    }
}
```

#### Event Type Initializer Pattern

Each module MUST have an ApplicationRunner that registers event types at startup:

```java
package com.positivity.{module}.internal.config;

import com.positivity.events.EventsApiConstants;
import com.positivity.events.EventTypeInitializerSupport;
import com.positivity.events.EventTypeRegistration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class {Module}EventTypeInitializer implements ApplicationRunner {
    private final RestClient restClient;
    private final EventTypeInitializerSupport initializerSupport;
    private final String apiSecret;

    public {Module}EventTypeInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.events.base-url:http://localhost:8085}") String eventServiceBaseUrl,
            @Value("${pos.events.api-secret:}") String apiSecret) {
        this.restClient = restClientBuilder.baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code").build();
        this.initializerSupport = new EventTypeInitializerSupport("pos-{module}");
        this.apiSecret = apiSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializerSupport.registerEventTypes({Module}EventTypes.all(), this::registerEventType);
    }

    private void registerEventType(EventTypeRegistration registration) {
        try {
            var request = restClient.put().uri("/{typeCode}", registration.getTypeCode())
                .contentType(MediaType.APPLICATION_JSON).body(registration);

            // Add shared secret header for authentication (avoids JWT circular dependency)
            if (EventsApiConstants.hasSecret(apiSecret)) {
                request.header(EventsApiConstants.SECRET_HEADER, apiSecret);
            }

            request.retrieve().toBodilessEntity();
        } catch (Exception e) {
            // Log warning but don't fail startup
        }
    }
}
```

#### Threshold Presets

| Preset     | p50   | p95   | p99   | Use Case                      |
| ---------- | ----- | ----- | ----- | ----------------------------- |
| `fastRead` | 50ms  | 200ms | 500ms | Simple GET/list operations    |
| `search`   | 100ms | 500ms | 1s    | Search/filter with pagination |
| `write`    | 200ms | 1s    | 3s    | POST/PUT/DELETE operations    |
| `approval` | 500ms | 2s    | 5s    | Workflow approval operations  |

#### Module Dependencies

Modules using `@EmitEvent` MUST include pos-events dependency in pom.xml:

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-events</artifactId>
    <version>${project.version}</version>
</dependency>
```

- Failing to register events results in silent audit failures and compliance violations.

### Domain Events over Kafka (ADR-0044)

Module-to-module communication is **events-only** — synchronous REST between domain modules is
prohibited; only the utility modules (gateway, security-service, documents, image, tax,
event-receiver, price) may be called synchronously. The `@EmitEvent`/pos-event-receiver pipeline
above is **audit-only** and is not a module-to-module channel. See
`docs/adr-0044-event-only-domain-walls.md` for the full rules.

- **Contracts live in `pos-domain-events`** (importable by every module): the
  `DomainEventEnvelope<T>` record and `DomainTopics` naming helpers. Payload DTOs are versioned
  per domain (additive-only within a version; breaking changes require a `.v2` topic).
- **Topics**: facts on `{domain}.events.v1` (published only by the owning module), commands on
  `{domain}.commands.v1` (consumed only by the owning module), poison messages to `{topic}.dlq`.
  Records are keyed by `aggregateId` (`envelope.recordKey()`), preserving per-aggregate order.
- **Producing**: build envelopes with `DomainEventEnvelope.of(...)` using the module's injected
  `Clock`; publish through the module's transactional outbox — never call `KafkaTemplate` directly
  from a business transaction.
- **Consuming**: record each `eventId` in the module's `processed_events` table in the same
  transaction as the replica update (redelivery must be harmless), and use `aggregateVersion` to
  ignore stale updates and detect gaps.
- **Replicas**: read-only copies of another domain's data live in `ext_{owner}_{entity}` tables,
  written only by the event consumer, with minimum fields required.
- **Reconciliation** (mandatory wherever a replica exists — "duplication without reconciliation is
  not permitted"): the owner publishes a `ReconciliationManifestV1` per closed time window on
  `{domain}.manifest.v1` (`DomainTopics.manifest(domain)`), summarizing the window's published
  events (count + SHA-256 checksum over sorted eventIds, `ReconciliationManifestV1.checksumOf`).
  Window membership is the UUIDv7 timestamp embedded in each `eventId`
  (`UuidV7Timestamps.instantOf`) — identical on both sides, no shared clock needed. The consumer
  recomputes the summary from its processing log (range-scan the eventId column with
  `UuidV7Timestamps.minStringAt(windowStart/End)`), and on mismatch increments a `replica.drift`
  counter (tags `owner`, `entity`) and publishes a `{domain}.outbox.replay-requested` command with
  `payload.since = windowStartUtc`; the owner re-emits through its outbox and the consumer's
  eventId dedupe makes the repair idempotent. Manifests are sent directly (not via outbox): a lost
  manifest self-heals on the owner's next run, and zero-event windows still get manifests so
  consumers can alert on absence. Reference pair: `pos-workorder` `ManifestPublisher` (owner) and
  `pos-customer` `WorkorderManifestListener` (consumer).

```java
DomainEventEnvelope<PartyUpdatedV1> event = DomainEventEnvelope.of(
        "customer.party.updated",   // eventType: dotted lowercase
        1,                          // schemaVersion
        partyId,                    // aggregateId (also the Kafka key)
        aggregateVersion,           // monotonic per-aggregate sequence
        "pos-customer",             // sourceService
        correlationId,              // nullable
        actorUserId,                // nullable; audit only, never authorization
        new PartyUpdatedV1(...),    // versioned payload DTO from pos-domain-events
        clock);
outbox.append(DomainTopics.events("customer"), event); // same transaction as the state change
```

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-domain-events</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Null Safety Standards

### ⚠️ MANDATORY: @NonNull Annotation for Null Safety

**All non-null parameters MUST use `@NonNull` annotation:**

- **`@NonNull`** (from `org.jspecify.annotations.NonNull`) provides **compile-time null safety** for IDE and static analysis tools

**Pattern:**

```java
import org.jspecify.annotations.NonNull;

public interface SomeService {
    EventType saveEventType(@NonNull EventType eventType);
    Optional<EventType> getEventType(@NonNull Long id);
}
```

**Why @NonNull:**

- Satisfies Eclipse's null analysis and IDE tooling at compile time
- Documents intent clearly for both tools and developers
- Prevents null pointer exceptions through static analysis
- Works seamlessly with Spring's null-safety framework

**Rules:**

- Use `@NonNull` for all non-null method parameters
- Use `@NonNull` for all non-null return types (except `Optional` and `void`)
- Use `@NotNull` (from `jakarta.validation.constraints.NotNull`) only for DTO/request validation contexts where Bean Validation is required
- Do NOT combine `@NonNull` and `@NotNull` on the same parameter - use only `@NonNull` for service/DAO layer methods

## Additional Documentation

- **Architecture Guide**: `$WORKSPACE/durion/docs/ARCHITECTURE_GUIDE.md` (Docker, ports, service communication, observability)
- **Development Guide**: `$WORKSPACE/durion/docs/DEVELOPMENT_GUIDE.md` (OpenAPI, POM, version management, pos-events)
- **Operations Runbook**: `$WORKSPACE/durion/docs/OPERATIONS_RUNBOOK.md` (operations, RBAC, permissions)

## Additional Agent Docs

- Backend test agent: `$WORKSPACE/durion-positivity-backend/.github/agents/test.agent.md`
- Java instructions: `$WORKSPACE/durion/.github/instructions/java.instructions.md` (Java code guidelines and best practices)

## Notes for Agents

- For incidents, follow cross-stack triage: frontend → gateway → backend service → DB.
