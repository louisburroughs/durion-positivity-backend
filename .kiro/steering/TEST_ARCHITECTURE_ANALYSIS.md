# Test Architecture Analysis: Repaired Tests (Repaired + Normalized)

## Overview

This document analyzes the architecture and patterns of the successfully repaired property-based tests in the `pos-agent-framework` module. This analysis serves as a blueprint for repairing remaining tests in the package.

**IMPORTANT**
Use the correct import statement for `SecurityContext`:

```java
import com.pos.agent.core.SecurityContext;
```

---

## Canonical Vocabulary (Authoritative)

This section is the source of truth for **domain** and **type** usage in repaired tests.

### AgentContext.domain (routing scope)

`AgentContext.domain` is the **primary routing signal** for agent selection. It is required in all requests.

**Canonical domain values used in repaired tests**:

* `architecture`
* `implementation`
* `testing`
* `deployment`
* `observability`
* `security`
* `performance`
* `documentation`
* `business`
* `integration`
* `event-driven`
* `configuration`
* `resilience`
* `collaboration`
* `registry`
* `multi-domain`
* `story`

> **Rule:** Do **not** duplicate domain in `properties` using `.property("domain", ...)` unless a test explicitly validates a “declared domain” field separate from routing. Default is: **domain lives only in `AgentContext.domain`.**

### AgentRequest.type (operation intent)

`AgentRequest.type` represents the **operation or request intent**. It may equal the domain, but it does not have to.

**Canonical type patterns**:

1. **type == domain** (common):

* `testing`, `security`, `resilience`, `business`, `documentation`, `collaboration`, `implementation`, `integration`

2. **type == sub-action** (allowed, common in integration/routing suites):

* `api-gateway`
* `api-versioning`
* `rest-api-design`
* `gateway-routing`
* `ci-cd-security`
* `security-scanning`
* `testing-pipeline`
* `availability-check`
* `domain-coverage`
* `registry-query`
* `agent-routing`
* `domain-query`
* `context-selection`
* `multi-domain-selection`
* `edge-case-selection`
* `complexity-analysis`
* `context-fallback`
* `domain-fallback`
* `universal-fallback`
* `agent-failure-fallback`
* `service-mapping-route`
* `context-fallback-route`
* `universal-fallback-route`
* `service-specific-route`
* `domain-based-route`
* `story-continuation`
* `story-activation-check`
* `story-variations`
* `story-determinism`
* `metadata-extraction`
* `label-preservation`
* `empty-body-handling`

> **Rule:** If a suite is testing routing/selection/fallback mechanics, `type` is often the *test intent*, while `context.domain` remains the *routing scope*.

---

## Core Architecture: API Structure

Most repaired tests use a consistent **Core API Pattern** based on these primary classes from `com.pos.agent.*` (with `SecurityContext` in `com.pos.agent.core`).

### 1) AgentManager — Central Request Processor

* **Purpose**: Processes all agent requests uniformly
* **Key Method**: `processRequest(AgentRequest)`
* **Returns**: `AgentResponse`
* **Instantiation**: Typically created once per test class as a field
* **Reuse**: Reused across multiple test cases within a class. (Thread-safety is not assumed unless explicitly verified by implementation; concurrency tests must not rely on implicit thread-safety.)

```java
private final AgentManager agentManager = new AgentManager();
```

### 2) AgentRequest — Request Object with Builder Pattern

* **Purpose**: Encapsulates request parameters and context
* **Key Fields**:

  * `type`: Operation intent (see Canonical Vocabulary)
  * `context`: `AgentContext` containing routing domain and properties
  * `securityContext`: `SecurityContext` for authorization
  * `requireTLS13`: Optional TLS requirement flag (only use in tests that explicitly validate TLS behavior)
* **Construction**: Always use builder pattern

```java
AgentRequest request = AgentRequest.builder()
    .type("testing")
    .context(context)
    .securityContext(security)
    .requireTLS13(true)
    .build();
```

### 3) AgentResponse — Response Object

* **Purpose**: Returns execution results
* **Key Methods**:

  * `isSuccess()`: boolean indicating if request succeeded
  * `getStatus()`: String describing response status (non-null on successful responses in repaired tests)
  * `getProcessingTimeMs()`: Long with execution time in milliseconds (present for performance-sensitive tests; do not assume universal presence unless verified)
* **Minimum Assertion Pattern**: All repaired tests assert `response.isSuccess()` is true and `response.getStatus()` is not null

```java
assertTrue(response.isSuccess());
assertNotNull(response.getStatus());
```

> **Note on semantics:** Some tests treat `getStatus()` as a human-readable message that may include guidance-like text. If your implementation later separates “status” from “guidance payload”, update content assertions accordingly.

### 4) AgentContext — Context Configuration

* **Purpose**: Provides routing domain and domain-specific properties
* **Key Fields**:

  * `domain`: Required routing domain (canonical values above)
  * `properties`: `Map<String, Object>` for domain-specific key-value pairs
* **Construction**: Always use builder pattern

```java
AgentContext context = AgentContext.builder()
    .domain("testing")
    .property("service", "pos-inventory")
    .property("topic", "tdd")
    .build();
```

> **Rule:** Prefer properties like `service`, `topic`, `scenario`, `pattern`, etc. Avoid `.property("domain", ...)` unless the test explicitly validates that redundancy.

### 5) SecurityContext — Authorization & Service Identity

* **Purpose**: Provides security context for all requests
* **Key Fields**:

  * `userId`: String identifying the user/tester
  * `roles`: List<String> of user roles
  * `permissions`: List<String> of granted permissions
  * `serviceId`: String identifying the calling service
  * `serviceType`: String categorizing service type
* **Construction**: Always use builder pattern
* **Usage Pattern**: Create once and reuse across all tests in a class

```java
private final SecurityContext security = SecurityContext.builder()
    .userId("manual-tester")
    .roles(List.of("tester"))
    .permissions(List.of("read", "execute"))
    .serviceId("pos-testing-suite")
    .serviceType("manual")
    .build();
```

---

## Test Patterns: Architecture and Common Usage

### Pattern 1: Manual Tests with Core APIs

**Files**: `TestingAgentManualTest.java`, `PairProgrammingNavigatorDebugTest.java`

**Structure**:

* `main()` method or `@Test` annotation
* Single `SecurityContext` for entire test
* Multiple `AgentRequest` instances with different contexts
* Print results for manual inspection

**Key Characteristics**:

* Simple assertion: `response.isSuccess()` and `response.getStatus()` not null
* Optional processing time tracking: `response.getProcessingTimeMs()` (only assert non-null if the implementation guarantees it in that test path)
* Multiple sequential requests in one test
* Debug-friendly output via `System.out.println()`

**Example Flow**:

```
1. Create SecurityContext (once)
2. For each scenario:
   a. Create AgentContext with domain + properties
   b. Create AgentRequest with type + context + security
   c. Process via agentManager.processRequest()
   d. Assert response.isSuccess()
   e. Assert response.getStatus() not null
```

### Pattern 2: Property-Based Tests with jqwik

**Files**: All `*PropertyTest.java` files

**Structure**:

* `@Property(tries = N)` annotation with configurable iteration count
* `@ForAll("generatorName")` parameters from provider methods
* `@Provide` methods generating test data via `Arbitraries`
* Optional `@Label` for documentation
* Assumptions via `Assume.that()` when filtering invalid variations is required

**Key Characteristics**:

* Same core API usage as manual tests
* Generates multiple random variations of input
* Validates properties hold across all variations
* Uses AssertJ `assertThat()` for richer assertions when needed
* Providers generate `AgentContext`, `String`, `List<String>`, or `AgentRequest` depending on the test

**Example Generator Pattern**:

```java
@Provide
Arbitrary<AgentContext> microserviceImplementationContexts() {
    return Arbitraries.of("pos-inventory", "pos-price", "pos-order")
        .map(service -> AgentContext.builder()
            .domain("testing")
            .property("service", service)
            .property("topic", "tdd")
            .build());
}
```

### Pattern 3: Domain-Specific Property Tests

**Files**: `DomainSpecificGuidanceQualityPropertyTest.java`, `DataStoreGuidanceAppropriatenessPropertyTest.java`

**Characteristics**:

* Multiple property tests in single class targeting different aspects
* Each `@Property` test validates one specific behavior
* Helper methods for validation (e.g., `containsArchitecturalConcepts()`)
* Multiple generators for different request types
* Validates guidance quality and content (if status carries content)

```java
@Property(tries = 100)
void domainSpecificGuidanceQuality(@ForAll("domainConsultationRequests") AgentRequest request) {
    AgentResponse response = agentManager.processRequest(request);
    assertTrue(response.isSuccess());
    // Additional domain-specific validations
}
```

---

## Domain-Specific Test Patterns (Normalized)

### Testing Domain

* **Domain Value**: `testing`
* **Common Request Types**: `testing`
* **Context Properties**: `service`, `topic`

### Collaboration Domain

* **Domain Value**: `collaboration`
* **Common Request Types**: `collaboration`
* **Context Properties**: `scenario`, `requestType`, `requiredAgents`

### Security Domain

* **Domain Value**: `security`
* **Common Request Types**: `security`, `security-scanning`
* **Context Properties**: vulnerability/auth scenario types

### Resilience Domain

* **Domain Value**: `resilience`
* **Common Request Types**: `resilience`
* **Context Properties**: `pattern` (circuit-breaker, retry, bulkhead, chaos-engineering)

### Business/POS Domain

* **Domain Value**: `business`
* **Common Request Types**: `business`
* **Context Properties**: `service`

### Implementation Domain

* **Domain Value**: `implementation`
* **Common Request Types**: `implementation`
* **Context Properties**: `service`, `pattern`, `framework`

### Event-Driven Domain

* **Domain Value**: `event-driven`
* **Common Request Types**: `event-driven`
* **Context Properties**: `topic`, `eventType`, `brokerType`, `processingType`

### Documentation Domain

* **Domain Value**: `documentation`
* **Common Request Types**: `documentation`
* **Context Properties**: `documentationType`, `synchronizationType`

### Integration / API Gateway Domain

* **Domain Value**: `integration`
* **Common Request Types**:

  * `api-gateway`, `api-versioning`, `rest-api-design`, `gateway-routing`
* **Context Properties**: `service`, `designType`, `versioningStrategy`, `routingStrategy`, `architecture`

### CI/CD Domain (STANDARDIZED)

* **Domain Value**: `ci-cd`  ✅ canonical
* **Common Request Types**:

  * `ci-cd-security`, `security-scanning`, `testing-pipeline`
* **Context Properties**: `scanningType`, `testType`, `feature`

---

## SecurityContext Patterns

### Pattern 1: Standard Testing SecurityContext

```java
private final SecurityContext security = SecurityContext.builder()
    .userId("test-user")
    .roles(List.of("developer"))
    .permissions(List.of("read", "execute"))
    .serviceId("pos-test-service")
    .serviceType("test")
    .build();
```

### Pattern 2: Domain-Specific SecurityContext

Common `serviceType` values:

* `manual` — manual tests
* `debug` — debug tests
* `test` — general tests
* `property` — property-based tests
* `collaboration` — collaboration tests
* `domain-test` — domain coverage / availability suites

Common `serviceId` values:

* `pos-testing-suite`
* `pos-debug-tests`
* `pos-impl-tests`
* `pos-security-tests`
* `pos-resilience-tests`
* `pos-domain-tests`

---

## AgentContext Property Patterns (Normalized)

### Universal Properties (Recommended)

```java
.property("service", "pos-<service>")              // Target POS microservice (when applicable)
.property("complexity", "high"|"medium"|"low")     // Optional complexity indicator
```

> Avoid `.property("domain", "<domain>")` by default; domain should be expressed through `AgentContext.domain`.

### Domain-Specific Properties (Examples)

**Testing**: `service`, `topic`
**Collaboration**: `scenario`, `requestType`, `requiredAgents`
**Event-driven**: `topic`, `eventType`, `brokerType`, `processingType`
**Documentation**: `documentationType`, `synchronizationType`
**CI/CD (`ci-cd`)**: `feature`, `scanningType`, `testType`

---

## Request Flow Pattern (Consistent Across Most Tests)

```
1. Initialization:
   ├─ Create AgentManager (once per test class)
   └─ Create SecurityContext (once per test class)

2. Per-Test Execution:
   ├─ Generate/Create AgentContext (domain + properties)
   ├─ Create AgentRequest (type + context + securityContext)
   ├─ Process via agentManager.processRequest(request)
   └─ Validate:
      ├─ response.isSuccess() == true
      └─ response.getStatus() != null
      (Optional: processing time assertions if the test defines performance requirements)
```

---

## Key Validation Points Across Tests

### Minimum Validations (Baseline)

```java
assertTrue(response.isSuccess());
assertNotNull(response.getStatus());
```

### Performance Validations (Only for tests that define thresholds)

```java
assertNotNull(response.getProcessingTimeMs());
assertTrue(response.getProcessingTimeMs() < THRESHOLD_MS);
```

### Content Validations (Use only when status carries meaningful text)

```java
assertThat(response.getStatus()).contains("architectural");
```

---

## Test Class Field Organization (Recommended)

Most repaired tests use this layout:

```java
public class ExamplePropertyTest {

    // Shared Manager
    private final AgentManager agentManager = new AgentManager();

    // Shared SecurityContext
    private final SecurityContext security = SecurityContext.builder()
        .userId("...")
        .roles(List.of(...))
        .permissions(List.of(...))
        .serviceId("...")
        .serviceType("...")
        .build();

    @BeforeEach
    void setUp() {
        // Optional initialization
    }

    @Property(tries = 100)
    void testMethod(@ForAll("generator") AgentContext context) { }

    @Provide
    Arbitrary<AgentContext> generator() { }
}
```

---

## Generator Patterns Used in Providers (Aligned)

### Pattern 1: String-to-AgentContext Mapping

```java
@Provide
Arbitrary<AgentContext> contexts() {
    return Arbitraries.of("option1", "option2", "option3")
        .map(option -> AgentContext.builder()
            .domain("implementation")
            .property("key", option)
            .build());
}
```

### Pattern 2: Cartesian Product

```java
@Provide
Arbitrary<AgentContext> contexts() {
    return Arbitraries.combine(
        Arbitraries.of("service1", "service2"),
        Arbitraries.of("topic1", "topic2")
    ).as((service, topic) -> AgentContext.builder()
        .domain("testing")
        .property("service", service)
        .property("topic", topic)
        .build());
}
```

### Pattern 3: Shuffled Agent Type Sets (jqwik-aligned)

```java
@Provide
Arbitrary<List<String>> agentTypeSets() {
    return Arbitraries.shuffledList(List.of(
        "testing", "implementation", "security", "resilience", "business"
    )).ofMinSize(2).ofMaxSize(4);
}
```

### Pattern 4: Generating AgentRequest Directly

```java
@Provide
Arbitrary<AgentRequest> requests() {
    return Arbitraries.of("pos-inventory", "pos-price")
        .map(service -> AgentRequest.builder()
            .type("implementation")
            .context(AgentContext.builder()
                .domain("implementation")
                .property("service", service)
                .build())
            .securityContext(createSecurityContext())
            .build());
}
```

---

## Special Cases & Variations (Explicitly Allowed)

### Case 1: Direct Agent Usage (Exception)

**File**: `EventSchemaConsistencyPropertyTest.java`

* Uses `EventDrivenArchitectureAgent` directly
* This is an exception used for specialized APIs or direct-agent seam testing

```java
private EventDrivenArchitectureAgent eventDrivenAgent;

@BeforeEach
void setUp() {
    eventDrivenAgent = new EventDrivenArchitectureAgent();
}
```

### Case 2: Assumptions in Tests

**File**: `CrossAgentCollaborationPropertyTest.java`
Use `Assume.that()` to filter invalid variations.

```java
@Property(tries = 100)
void test(@ForAll("data") List<String> data) {
    Assume.that(data.size() >= 4);
    // test code
}
```

### Case 3: Lazy Setup (Alternative Pattern)

Some tests may use lazy initialization where required by legacy structure. Prefer final-field initialization unless the test explicitly depends on delayed construction.

```java
private AgentManager agentManager;

private void ensureSetup() {
    if (agentManager == null) {
        agentManager = new AgentManager();
    }
}
```

---

## Key Takeaways for Repairing Other Tests (Repaired)

### 1) Prefer Core API Pattern

* Use `AgentManager.processRequest(AgentRequest)`
* Direct agent instantiation is allowed only when a test is explicitly validating a specialized agent seam

### 2) Security Context Setup

* Create once per test class as a field
* Use consistent `userId`, `roles`, `permissions`
* Match `serviceType` and `serviceId` to test purpose

### 3) Context Domain is Mandatory (and canonical)

* `AgentContext.domain` must be set
* Do not duplicate domain inside properties by default
* Properties must align with domain expectations

### 4) Response Validation Pattern

* Always validate `isSuccess()`
* Always validate `getStatus()` is not null
* Only assert on `getProcessingTimeMs()` when the test defines a performance requirement
* Use AssertJ for complex validations

### 5) Property-Based Test Structure

* Use jqwik `@Property` + `@Provide` + `@ForAll`
* Use `Assume.that()` when needed to filter invalid combinations
* Use try counts appropriate to scope (100 for core routing/selection; 50 for heavy collaboration/concurrency suites)

### 6) Generator Patterns

* Map simple values to contexts
* Combine values for realistic combinations
* Use `Arbitraries.shuffledList` for sets

---

## Summary Table: Test Coverage by Domain (Normalized)

| Domain         | Example Test Classes                                 | Common Request Types                                          | Key Properties                    |
| -------------- | ---------------------------------------------------- | ------------------------------------------------------------- | --------------------------------- |
| testing        | TestingAgentManualTest                               | testing                                                       | service, topic                    |
| collaboration  | PairProgramming*                                     | collaboration                                                 | scenario, requestType             |
| security       | SecurityComplianceValidationPropertyTest             | security, security-scanning                                   | scenario, vulnerability           |
| resilience     | ResiliencePatternEffectivenessPropertyTest           | resilience                                                    | pattern                           |
| business       | POSDomainPatternAdherencePropertyTest                | business                                                      | service                           |
| implementation | SpringBoot* / DataStore*                             | implementation                                                | service, framework, dataStoreType |
| event-driven   | EventSchemaConsistencyPropertyTest                   | event-driven                                                  | topic, eventType                  |
| documentation  | DocumentationSynchronizationPropertyTest             | documentation                                                 | documentationType                 |
| observability  | ObservabilityInstrumentationCompletenessPropertyTest | observability                                                 | instrumentationType               |
| integration    | ApiGatewayIntegrationConsistencyPropertyTest         | api-gateway, api-versioning, rest-api-design, gateway-routing | service, routingStrategy          |
| ci-cd          | CICDSecurityIntegrationPropertyTest                  | ci-cd-security, security-scanning, testing-pipeline           | scanningType, testType            |

---

## Architecture Diagram: Request Processing Flow

```
Test Class
  ├── Fields:
  │   ├── AgentManager agentManager (shared)
  │   └── SecurityContext security (shared)
  │
  ├── Setup (Optional):
  │   └── @BeforeEach setUp()
  │
  └── Test Methods:
      ├── Manual @Test or Property @Property(tries=N)
      │
      ├── For each test iteration:
      │   ├── Generate/Create AgentContext
      │   │   ├── Set domain (canonical)
      │   │   └── Set properties (domain-specific)
      │   │
      │   ├── Create AgentRequest
      │   │   ├── .type("...") (intent)
      │   │   ├── .context(agentContext)
      │   │   ├── .securityContext(security)
      │   │   └── .build()
      │   │
      │   ├── Process: agentManager.processRequest(request)
      │   │   └── Returns: AgentResponse
      │   │
      │   └── Validate:
      │       ├── response.isSuccess() ✓
      │       └── response.getStatus() ✓
      │       (Optional: processing time / content assertions where defined)
      │
      └── Providers (@Provide):
          ├── Arbitrary<AgentContext>
          ├── Arbitrary<String>
          ├── Arbitrary<List<String>>
          └── Arbitrary<AgentRequest>
```
