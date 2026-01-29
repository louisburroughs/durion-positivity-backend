# AGENTS.md — durion-positivity-backend

## Project Overview

POS backend microservice suite for Durion. Multi-module Maven project containing gateway + `pos-*` services (Spring Boot 3.x, Java 21).

## Quick Prerequisites

- Java 21+
- Maven (use `./mvnw` wrapper)
- Docker for local test stacks

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

## Observability (backend-focused)

- Prefer OpenTelemetry Java agent for baseline; use manual SDK instrumentation for high-value business metrics.
- Attach attributes: `service.name`, `service.version`, `deployment.environment`, `container_id`, `component`, `status`.
- Expose Actuator endpoints (`/actuator/health`, `/actuator/prometheus`) where applicable for monitoring.
- Reference: `../docs/architecture/observability/OBSERVABILITY.md` and `.github/agents/sre.agent.md`.

## Module Conventions & Intermodule Communication

Treat each `pos-*` directory as a standard Spring Boot service using existing module patterns:

- `service/` – business logic orchestration (public API for Modulith)
- `internal/controller/` – REST endpoints (keep controllers thin)
- `internal/repository/` – Spring Data JPA data access
- `internal/entity/` – JPA entities and domain types
- `internal/config/` – Spring configuration (security, DB, messaging)
- `internal/dto/` – Data transfer objects
- `internal/domain/` – Domain models
- `internal/enums/` – Enumerations

### ⚠️ MANDATORY: Internal Package Structure

**All code MUST reside in `com.positivity.{domain}.internal` packages EXCEPT service layer.** This is strictly enforced:

- **ONLY `service/` packages** (e.g., `com.positivity.accounting.service`) are exposed as the public API for other modules via Modulith
- **The `@SpringBootApplication` class** (e.g., `PosAccountingApplication.java`) MUST remain in the root `com.positivity.{domain}` package for proper component scanning
- **ALL other packages MUST be under `internal/`**: `internal/controller`, `internal/repository`, `internal/entity`, `internal/dto`, `internal/config`, `internal/domain`, `internal/enums`, etc.
- **Controllers, repositories, entities, DTOs, configs** are implementation details and MUST NOT be accessed directly by other modules
- **Cross-module access** happens ONLY through service interfaces and Modulith events
- This encapsulation prevents tight coupling and ensures modules remain independently deployable and maintainable

**Package structure example:**
```
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

### ⚠️ MANDATORY: Modulith for Intermodule Communications

**All cross-module communication within this backend MUST use Spring Modulith.** This is non-negotiable:

- **Do NOT use direct Spring Data JPA repository access** across module boundaries.
- **Do NOT use REST calls** between internal modules (only for external services).
- **Use Modulith events and module API exports** for decoupled, reliable intermodule interactions.
- **Use the same DTOs for Modulith calls as for REST API calls** to ensure consistency between internal and external interfaces.
- Reference the Modulith documentation and module structure for proper event publishing and listener patterns.
- Violating this constraint risks tight coupling, circular dependencies, and maintainability debt.

## Events & Cross-Cutting Concerns

### ⚠️ MANDATORY: pos-events Annotations for API Event Logging

**All API events MUST be logged using `pos-events` annotations.** This is required for audit trails, observability, and compliance:

- **Use `@ApiEvent` annotations** on all REST controller methods that perform state changes or significant actions.
- **Pre-register all event types** in the module's `SpringBootApplication` startup configuration.
- **Define the event registry** by implementing an `@Configuration` class that registers event schemas on application startup.
- **Event registration must occur before any controllers are instantiated** to ensure events are tracked from the first request.
- Example pattern:

  ```java
  @Configuration
  public class EventRegistryConfig {
      @Bean
      public EventRegistry eventRegistry(PosEventService eventService) {
          return new EventRegistry()
              .register("order.created", OrderCreatedEvent.class)
              .register("order.updated", OrderUpdatedEvent.class)
              .register("payment.processed", PaymentProcessedEvent.class);
      }
  }
  ```

- Failing to register events results in silent audit failures and compliance violations.

## Null Safety Standards

### ⚠️ MANDATORY: @NonNull Annotation for Null Safety

**All non-null parameters MUST use `@NonNull` annotation:**

- **`@NonNull`** (from `org.springframework.lang.NonNull`) provides **compile-time null safety** for IDE and static analysis tools

**Pattern:**

```java
import org.springframework.lang.NonNull;

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

## Useful Commands

```bash
# Build and run gateway
./mvnw -pl pos-api-gateway -am spring-boot:run
# Run a module's tests
./mvnw -pl pos-order -am test
```

## Agent Docs to Consult

- `.github/agents/sre.agent.md` (observability)
- `.github/agents/dev-deploy.agent.md` (deploy/CI guidance)
- `../AGENTS.md` (workspace-level guidance)
- Backend test agent: `.github/agents/test.agent.md`
- Java instructions: `../.github/instructions/java.instructions.md` (Java code guidelines and best practices)

## Notes for Agents

- Do not hardcode credentials in CI or code. Use environment variables or secret stores.
- For incidents, follow cross-stack triage: frontend → gateway → backend service → DB.
