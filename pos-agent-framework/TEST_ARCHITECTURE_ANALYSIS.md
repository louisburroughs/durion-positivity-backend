# Test Architecture Analysis: Repaired Tests

## Overview
This document analyzes the architecture and patterns of the successfully repaired property-based tests in the `pos-agent-framework` module. This analysis serves as a blueprint for repairing remaining tests in the package.

**!IMPORTANT** - Use the correct import startement for SecurityContext (import com.pos.agent.core.SecurityContext;)
---

## Core Architecture: API Structure

All repaired tests use a consistent **Core API Pattern** based on these primary classes from `com.pos.agent.*`:

### 1. **AgentManager** - Central Request Processor
- **Purpose**: Processes all agent requests uniformly
- **Key Method**: `processRequest(AgentRequest)`
- **Returns**: `AgentResponse`
- **Instantiation**: Typically created once per test class as a field
- **Thread-safe**: Safe for reuse across multiple test cases

```java
private final AgentManager agentManager = new AgentManager();
```

### 2. **AgentRequest** - Request Object with Builder Pattern
- **Purpose**: Encapsulates request parameters and context
- **Key Fields**:
  - `type`: Agent type being invoked (e.g., "testing", "resilience", "collaboration")
  - `context`: AgentContext object containing domain and properties
  - `securityContext`: SecurityContext for authorization
  - `requireTLS13`: Optional TLS requirement flag (used in some tests)
- **Construction**: Always use builder pattern
- **Example**:
```java
AgentRequest request = AgentRequest.builder()
    .type("testing")
    .context(context)
    .securityContext(security)
    .requireTLS13(true)
    .build();
```

### 3. **AgentResponse** - Response Object
- **Purpose**: Returns execution results
- **Key Methods**:
  - `isSuccess()`: boolean indicating if request succeeded
  - `getStatus()`: String describing response status
  - `getProcessingTimeMs()`: Long with execution time in milliseconds
- **Assertion Pattern**: All tests assert `response.isSuccess()` is true and `response.getStatus()` is not null

```java
assertTrue(response.isSuccess());
assertNotNull(response.getStatus());
```

### 4. **AgentContext** - Context Configuration
- **Purpose**: Provides domain-specific context and properties
- **Key Fields**:
  - `domain`: String representing the logical domain (e.g., "testing", "collaboration", "security")
  - `properties`: Map<String, Object> for domain-specific key-value pairs
- **Construction**: Always use builder pattern
- **Common Properties** (domain-dependent):
  - `service`: Microservice name (e.g., "pos-inventory", "pos-price")
  - `topic`: Specific topic within domain (e.g., "tdd", "property-based-testing")
  - `scenario`: Detailed scenario description
  - `requestType`: Type of request within domain
  - `complexity`: Complexity level (e.g., "high")
  - `requiredAgents`: Number of agents to collaborate

```java
AgentContext context = AgentContext.builder()
    .domain("testing")
    .property("service", "pos-inventory")
    .property("topic", "tdd")
    .build();
```

### 5. **SecurityContext** - Authorization & Service Identity
- **Purpose**: Provides security context for all requests
- **Key Fields**:
  - `userId`: String identifying the user/tester
  - `roles`: List<String> of user roles
  - `permissions`: List<String> of granted permissions
  - `serviceId`: String identifying the calling service
  - `serviceType`: String categorizing service type
- **Construction**: Always use builder pattern
- **Usage Pattern**: Create once and reuse across all tests in a class

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
- Direct `main()` method or `@Test` annotation
- Single SecurityContext for entire test
- Multiple AgentRequest instances with different contexts
- Print results for manual inspection

**Key Characteristics**:
- Simple assertion: `response.isSuccess()` and `response.getStatus()` not null
- Processing time tracking: `response.getProcessingTimeMs()`
- Multiple sequential requests in one test
- Debug-friendly output via `System.out.println()`

**Example Flow**:
```
1. Create SecurityContext (once)
2. For each test scenario:
   a. Create AgentContext with domain and properties
   b. Create AgentRequest with context and security
   c. Process via agentManager.processRequest()
   d. Assert response.isSuccess()
   e. Assert response.getStatus() not null
```

### Pattern 2: Property-Based Tests with jqwik
**Files**: All `*PropertyTest.java` files

**Structure**:
- `@Property(tries = N)` annotation with configurable iteration count
- `@ForAll("generatorName")` parameters from provider methods
- `@Provide` methods generating test data via Arbitraries
- Optional `@Label` for test documentation
- Assumptions via `Assume.that()`

**Key Characteristics**:
- Same core API usage as manual tests
- Generates multiple random variations of input
- Validates properties hold across all variations
- Uses assertThat() from AssertJ for sophisticated assertions
- Providers generate AgentContext, String, or List<String> parameters

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
- Multiple property tests in single class targeting different aspects
- Each @Property test validates one specific behavior
- Helper methods for validation (e.g., `containsArchitecturalConcepts()`)
- Multiple generators for different request types
- Validates guidance quality and content

**Example**:
```java
@Property(tries = 100)
void domainSpecificGuidanceQuality(@ForAll("domainConsultationRequests") AgentRequest request) {
    AgentResponse response = agentManager.processRequest(request);
    assertTrue(response.isSuccess());
    // Additional domain-specific validations
}
```

---

## Domain-Specific Test Patterns

### Testing Domain
- **Classes**: `TestingAgentManualTest.java`
- **Domain Value**: "testing"
- **Request Types**: "testing"
- **Context Properties**: service, topic
- **Topics**: "tdd", "property-based-testing"

### Collaboration/Pair Programming Domain
- **Classes**: `PairProgrammingLoopDetectionPropertyTest.java`, `PairProgrammingNavigatorDebugTest.java`
- **Domain Value**: "collaboration"
- **Request Types**: "collaboration"
- **Scenarios**: loop-detection, architectural-drift, scope-creep
- **Context Properties**: scenario, requestType

### Security Domain
- **Classes**: `SecurityComplianceValidationPropertyTest.java`
- **Domain Value**: "security"
- **Request Types**: "security"
- **Context Properties**: vulnerability types, authentication scenarios

### Resilience Domain
- **Classes**: `ResiliencePatternEffectivenessPropertyTest.java`
- **Domain Value**: "resilience"
- **Request Types**: "resilience"
- **Patterns**: circuit-breaker, retry, bulkhead, chaos-engineering

### Business/POS Domain
- **Classes**: `POSDomainPatternAdherencePropertyTest.java`
- **Domain Value**: "business"
- **Request Types**: "business"
- **Services**: pos-inventory, pos-price, pos-order, pos-customer

### Implementation Domain
- **Classes**: Multiple tests
- **Domain Value**: "implementation"
- **Request Types**: "implementation"
- **Patterns**: Spring Boot, data access, microservices

### Event-Driven Domain
- **Classes**: `EventSchemaConsistencyPropertyTest.java`
- **Domain Value**: "event-driven"
- **Uses**: `EventDrivenArchitectureAgent` directly
- **Focuses**: Event schemas, idempotency, message brokers

### Documentation Domain
- **Classes**: `DocumentationSynchronizationPropertyTest.java`
- **Domain Value**: "documentation"
- **Request Types**: "documentation"
- **Focuses**: API docs, technical docs, synchronization

### API Gateway/Integration Domain
- **Classes**: `ApiGatewayIntegrationConsistencyPropertyTest.java`, `CICDSecurityIntegrationPropertyTest.java`
- **Domain Value**: `"integration"` (for API Gateway), `"ci-cd"` (for CICD), `"security"` (for security scanning), `"testing"` (for test pipelines)
- **Request Types**: 
  - `"api-gateway"` - General API Gateway requests
  - `"api-versioning"` - API versioning strategies
  - `"rest-api-design"` - REST API design patterns
  - `"gateway-routing"` - API Gateway routing configuration
  - `"ci-cd-security"` - CI/CD security integration
  - `"security-scanning"` - Security scanning configuration
  - `"testing-pipeline"` - Test automation pipelines
- **Services**: pos-api-gateway, pos-customer, pos-catalog, pos-order, pos-vehicle, pos-invoice
- **Context Properties**: 
  - `service` - Target POS microservice
  - `designType` / `designPattern` - Type of design (rest-api, resource, collection, nested, query, command)
  - `versioningStrategy` - Versioning approach (v1-to-v2, backward-compat, deprecation, contract-testing, schema-evolution)
  - `routingStrategy` - Routing type (path-based, header-based, load-balancing, circuit-breaker, timeout-retry)
  - `architecture` - Architecture type (microservices, monolithic, serverless, containerized, hybrid)
  - `scanningType` - Security scan type (sast, dast, dependency, container, combined)
  - `testType` - Test category (unit, integration, contract, security, end-to-end)
- **Focuses**: API consistency, OpenAPI/Swagger specs, HTTP best practices, versioning, routing, resilience, security scanning, test automation

**Patterns**:
- Multiple property tests (3-5) per test class validating different aspects
- Generator returns `AgentContext` (not custom request objects)
- Domain contexts mapped from simple string generators via `.map()`
- Performance tests included (3-second timeout for API operations)
- Response validation: `isSuccess()`, `getStatus()`, `getProcessingTimeMs()`

### Domain Coverage Pattern
- **Class**: `AgentDomainCoveragePropertyTest.java`
- **Purpose**: Validates comprehensive coverage across core domains (architecture, implementation, testing, deployment)
- **Request Type**: `"domain-coverage"`
- **SecurityContext**: `serviceType("domain-test")`, `serviceId("pos-domain-tests")`
- **Key Pattern**: 
  - Generate AgentContext for each domain independently
  - Iterate through all domains validating each
  - Use List<AgentContext> when testing all domains together
  - Validate processing time < 3000ms for domain tests
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> domainContexts() {
      return Arbitraries.of("architecture", "implementation", "testing", "deployment")
          .map(domain -> AgentContext.builder()
              .domain(domain)
              .property("coverageType", "comprehensive")
              .property("domain", domain)
              .build());
  }
  
  @Provide
  Arbitrary<List<AgentContext>> allDomainContexts() {
      return Arbitraries.just(List.of(
          AgentContext.builder().domain("architecture").property("domain", "architecture").build(),
          AgentContext.builder().domain("implementation").property("domain", "implementation").build(),
          AgentContext.builder().domain("testing").property("domain", "testing").build(),
          AgentContext.builder().domain("deployment").property("domain", "deployment").build()
      ));
  }
  ```
- **Test Pattern**:
  1. **agentDomainCoverage()** - Tests single domain context, validates response for specific domain
  2. **allCoreDomainsCovered()** - Iterates through List<AgentContext>, validates all domains return success
  3. **guidanceProvisionPerformance()** - Validates domain coverage completes within performance threshold
- **Special Considerations**:
  - Use single AgentContext when testing individual domain coverage
  - Use List<AgentContext> when validating all domains are covered in one call
  - Performance threshold: 3000ms (allows time for all domains)
  - Try count: 100 (ensures all domain combinations tested)
  - Assertion pattern: Check isSuccess(), getStatus(), and getProcessingTimeMs()

### Agent Availability Pattern
- **Class**: `AgentAvailabilityAndDomainCoveragePropertyTest.java`
- **Purpose**: Validates agent availability across all major domains (architecture, implementation, testing, deployment, observability, security, performance, documentation)
- **Request Types**: `"domain-test"`, `"domain-coverage"`, `"availability-check"`
- **SecurityContext**: `serviceType("domain-test")`, `serviceId("test-service")`
- **Key Pattern**:
  - Test that agents are discoverable for each major domain
  - Validate domain coverage is comprehensive (all 8 domains)
  - Ensure availability discovery completes within 1 second
  - Use single AgentContext per domain in generators
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> majorDomains() {
      return Arbitraries.of(
              "architecture", "implementation", "testing", "deployment",
              "observability", "security", "performance", "documentation")
          .map(domain -> AgentContext.builder()
              .domain(domain)
              .property("domainType", domain)
              .build());
  }
  ```
- **Test Pattern**:
  1. **agentAvailabilityAndDomainCoverage()** - Validates that agents are available for any major domain
  2. **allMajorDomainsHaveCoverage()** - Ensures all 8 major domains have corresponding agents
  3. **agentAvailabilityPerformance()** - Validates agent discovery completes within 1 second
- **Special Considerations**:
  - Eight major domains: architecture, implementation, testing, deployment, observability, security, performance, documentation
  - Request types align with test purpose: availability-check for performance timing
  - Performance threshold: 1000ms (agent availability must be quick)
  - Use simple property names (domainType) matching domain value
  - Try count: 100 (ensures all domain combinations tested)
  - Assertion pattern: Check isSuccess(), getStatus(), and getProcessingTimeMs() < 1000

### Agent Collaboration Pattern
- **Class**: `AgentCollaborationConsistencyPropertyTest.java`
- **Purpose**: Validates consistency across multiple agent types collaborating in scenarios
- **Request Types**: Various (collaboration, workflow, conflict-resolution, etc.)
- **SecurityContext**: `serviceType("collaboration")`, `serviceId("pos-collaboration-tests")`
- **Key Pattern**:
  - Generate scenarios combining multiple agent types
  - Test consistency across different agent type combinations
  - Validate conflict resolution maintains quality
  - Use shuffled agent type lists for variety
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> collaborationScenarios() {
      return Arbitraries.of("pair-programming", "code-review", "refactoring", "debugging")
          .map(scenario -> AgentContext.builder()
              .domain("collaboration")
              .property("scenario", scenario)
              .property("requiredAgents", 2)
              .build());
  }
  
  @Provide
  Arbitrary<List<String>> agentTypeSets() {
      return Arbitraries.shuffledList(List.of(
          "testing", "implementation", "security", "resilience", "business"
      )).ofMinSize(2).ofMaxSize(4);
  }
  
  @Provide
  Arbitrary<AgentContext> conflictingScenarios() {
      return Arbitraries.of("architectural-conflict", "design-conflict", "implementation-conflict")
          .map(conflict -> AgentContext.builder()
              .domain("collaboration")
              .property("conflictType", conflict)
              .property("resolutionApproach", "consensus")
              .build());
  }
  
  @Provide
  Arbitrary<AgentContext> workflowDomains() {
      return Arbitraries.of("microservices", "monolithic", "serverless", "hybrid")
          .map(workflow -> AgentContext.builder()
              .domain("collaboration")
              .property("workflowDomain", workflow)
              .property("moduleName", "test-module")
              .build());
  }
  ```
- **Test Pattern**:
  1. **collaborationConsistency()** - Validates agents collaborate consistently in scenarios
  2. **consistencyValidationPerformance()** - Ensures collaboration completes within 3000ms
  3. **conflictResolutionMaintainsQuality()** - Validates quality maintained during conflict resolution
  4. **collaborationWorkflowsAreConsistent()** - Tests consistency across workflow domains
- **Special Considerations**:
  - Use List<String> for agent type combinations (shuffle for variety)
  - Scenarios should be realistic collaboration patterns (pair-programming, code-review, etc.)
  - Conflict resolution must maintain success status (quality check)
  - Performance threshold: 3000ms (allows for multi-agent interaction)
  - Try count: 50 (collaboration scenarios more complex, fewer iterations sufficient)
  - Assertion pattern: Check isSuccess(), getStatus(), and getProcessingTimeMs()
  - Agent type shuffling: Different orderings test different collaboration sequences

### Story Processing Continuation Pattern
- **Class**: `ProcessingContinuationPropertyTest.java`
- **Purpose**: Validates story request processing continues appropriately based on content validity and story type
- **Request Types**: `"story-continuation"`, `"story-activation-check"`, `"story-variations"`, `"story-determinism"`
- **SecurityContext**: `serviceType("property")`, `serviceId("pos-story-tests")`
- **Key Pattern**:
  - Generate story contexts with validity flags (isBackendStory, isValidContent)
  - Test that processing continues only when all conditions are met
  - Validate consistency across variations and deterministic behavior
  - Use boolean properties to track activation conditions
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> validStories() {
      return Arbitraries.of(
              "User Authentication",
              "Order Processing",
              "Payment Integration",
              "Inventory Management")
          .map(storyName -> AgentContext.builder()
              .domain("story")
              .property("storyName", storyName)
              .property("isBackendStory", true)
              .property("isValidContent", true)
              .build());
  }
  
  @Provide
  Arbitrary<AgentContext> storiesWithVariedValidity() {
      return Arbitraries.oneOf(
              validStories(),
              invalidBackendStories(),
              invalidContentStories());
  }
  ```
- **Test Pattern**:
  1. **processingContinuationOnValidStories()** - Validates processing continues for stories meeting all conditions
  2. **allActivationConditionsMustBeMet()** - Ensures processing only continues when both isBackendStory and isValidContent are true
  3. **validStoriesWithVariationsPass()** - Tests that valid stories pass regardless of content length variations
  4. **processingContinuationIsDeterministic()** - Validates same story produces identical results on multiple calls
- **Special Considerations**:
  - Use boolean properties (isBackendStory, isValidContent) to encode activation conditions
  - Response success should correlate with condition flags: `response.isSuccess() == shouldContinue`
  - Create separate generators for invalid scenarios (invalidBackendStories, invalidContentStories)
  - Performance: No explicit threshold (use default response timing)
  - Try count: 100 (story processing widely used, high confidence needed)
  - Assertion pattern: Check isSuccess(), getStatus(), and boolean condition correlation

### Metadata Extraction Completeness Pattern
- **Class**: `MetadataExtractionCompletenessPropertyTest.java`
- **Purpose**: Validates complete and accurate extraction of story metadata fields (title, body, labels, repository)
- **Request Types**: `"metadata-extraction"`, `"label-preservation"`, `"empty-body-handling"`
- **SecurityContext**: `serviceType("property")`, `serviceId("pos-metadata-tests")`
- **Key Pattern**:
  - Generate story contexts with varying metadata configurations
  - Test that all metadata fields are extracted without loss
  - Validate handling of edge cases (empty bodies, missing labels)
  - Use boolean flags to mark presence/absence of optional fields
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> validStoryIssues() {
      return Arbitraries.strings()
              .withCharRange('a', 'z')
              .withCharRange('A', 'Z')
              .ofMinLength(10)
              .ofMaxLength(100)
              .map(title -> AgentContext.builder()
                  .domain("story")
                  .property("title", title)
                  .property("repository", "durion-positivity-backend")
                  .property("hasLabels", true)
                  .property("hasBody", true)
                  .build());
  }
  
  @Provide
  Arbitrary<AgentContext> issuesWithEmptyBodies() {
      return Arbitraries.strings()
              .withCharRange('a', 'z')
              .ofMinLength(10)
              .ofMaxLength(100)
              .map(title -> AgentContext.builder()
                  .domain("story")
                  .property("title", title)
                  .property("repository", "durion-positivity-backend")
                  .property("hasLabels", false)
                  .property("hasBody", false)
                  .build());
  }
  ```
- **Test Pattern**:
  1. **metadataExtractionCompleteness()** - Validates all metadata fields extracted successfully
  2. **metadataExtractionPreservesLabels()** - Ensures label information preserved completely
  3. **metadataExtractionHandlesEmptyBodies()** - Tests handling of edge case (no body content)
- **Special Considerations**:
  - Use boolean flags (hasLabels, hasBody) to encode content variations
  - Store metadata in simple string properties (title, repository) for validation
  - Test edge cases separately: empty bodies, missing labels, minimal titles
  - No performance threshold needed (metadata extraction is lightweight)
  - Try count: 100 (metadata extraction critical for all story processing)
  - Assertion pattern: Check isSuccess(), getStatus() for simple validation

### Agent Registry and Multi-Domain Discovery Pattern
- **Class**: `EnhancedAgentRegistryIntegrationTest.java`
- **Purpose**: Validates agent discovery, request routing, and domain-based matching across 15+ specialized agent domains
- **Request Types**: `"registry-query"`, `"agent-routing"`, `"domain-query"`, `"concurrent-routing-1/2"`
- **SecurityContext**: `serviceType("property")`, `serviceId("pos-registry-tests")`
- **Key Pattern**:
  - Generate contexts for different agent domains (15 domains total)
  - Test discovery and routing for specialized agents
  - Validate concurrent request handling across domains
  - Use domain property to encode target agent type
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> registryDomains() {
      return Arbitraries.of(
              "architecture", "implementation", "testing", "deployment",
              "security", "observability", "documentation", "business",
              "integration", "pair-programming", "governance",
              "event-driven", "cicd", "configuration", "resilience")
          .map(domain -> AgentContext.builder()
              .domain("registry")
              .property("queryDomain", domain)
              .build());
  }
  
  @Provide
  Arbitrary<AgentContext> eventDrivenContexts() {
      return Arbitraries.of(
              "kafka-streaming",
              "event-sourcing",
              "message-brokers",
              "event-schemas")
          .map(topic -> AgentContext.builder()
              .domain("event-driven")
              .property("topic", topic)
              .build());
  }
  
  @Provide
  Arbitrary<AgentContext> cicdContexts() {
      return Arbitraries.of(
              "build-automation",
              "deployment-strategies",
              "security-scanning",
              "test-automation")
          .map(feature -> AgentContext.builder()
              .domain("cicd")
              .property("feature", feature)
              .build());
  }
  ```
- **Test Pattern**:
  1. **agentDiscoveryAcrossDomains()** - Tests discovery for all 15 agent domains
  2. **requestRoutingToSpecializedAgents()** - Validates routing to appropriate specialized agents
  3. **eventDrivenAgentDiscovery()** - Tests event-driven domain (kafka, event-sourcing, etc.)
  4. **cicdAgentDiscovery()** - Tests CI/CD domain (build, deployment, security scanning)
  5. **configurationAgentDiscovery()** - Tests configuration domain (feature flags, Spring Cloud Config)
  6. **resilienceAgentDiscovery()** - Tests resilience domain (circuit breakers, retry patterns)
  7. **concurrentMultiDomainRouting()** - Tests concurrent requests across multiple domains
- **Special Considerations**:
  - 15 specialized agent domains: architecture, implementation, testing, deployment, security, observability, documentation, business, integration, pair-programming, governance, event-driven, cicd, configuration, resilience
  - Use separate generators for each domain to encode domain-specific properties
  - Domain-specific topics/features: kafka for event-driven, jenkins for CI/CD, feature flags for config, circuit breakers for resilience
  - Concurrent routing uses try count of 50 (multi-domain interactions more complex)
  - Domain discovery uses try count of 100 (core registry functionality)
  - Assertion pattern: Check isSuccess(), getStatus() for all domain types

### Context-Based Agent Selection and Routing Pattern
- **Class**: `ContextBasedAgentSelectorTest.java`
- **Purpose**: Validates agent selection based on technical context keywords and multi-domain scenarios
- **Request Types**: `"context-selection"`, `"multi-domain-selection"`, `"edge-case-selection"`, `"complexity-analysis"`
- **SecurityContext**: `serviceType("property")`, `serviceId("pos-routing-tests")`
- **Key Pattern**:
  - Generate contexts with different technical keywords (Spring Boot, security, architecture, testing, deployment, etc.)
  - Test multi-domain context handling (e.g., "Spring Boot with Docker and security")
  - Validate edge case handling (empty, null, unrecognized contexts)
  - Analyze context complexity (simple vs complex technical descriptions)
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> technicalContexts() {
      return Arbitraries.of(
              "implementation:Spring Boot microservice",
              "security:JWT authentication",
              "architecture:Microservices design",
              "testing:JUnit integration tests",
              "deployment:Docker containerization",
              "event-driven:Kafka streaming",
              "cicd:Jenkins pipeline",
              "configuration:Spring Cloud Config",
              "resilience:Circuit breaker patterns",
              "business:POS inventory management"
      ).map(contextStr -> {
          String[] parts = contextStr.split(":");
          return AgentContext.builder()
                  .domain(parts[0])
                  .property("technicalContext", parts[1])
                  .build();
      });
  }
  
  @Provide
  Arbitrary<AgentContext> multiDomainContexts() {
      return Arbitraries.of(
              "Spring Boot with Docker and security",
              "Microservices with Kafka and testing",
              "CI/CD with deployment and resilience"
      ).map(description ->
              AgentContext.builder()
                      .domain("multi-domain")
                      .property("description", description)
                      .property("isComplex", true)
                      .build()
      );
  }
  ```
- **Test Pattern**:
  1. **contextBasedAgentSelection()** - Tests agent selection for 10 technical domains
  2. **multiDomainContextHandling()** - Validates handling of complex multi-domain scenarios
  3. **edgeCaseContextHandling()** - Tests edge cases (empty, unrecognized, minimal contexts)
  4. **complexityAnalysis()** - Validates context complexity detection
- **Special Considerations**:
  - 10 technical domains: implementation, security, architecture, testing, deployment, event-driven, cicd, configuration, resilience, business
  - Multi-domain contexts use isComplex flag for complexity tracking
  - Edge cases use isEdgeCase flag to mark special handling scenarios
  - Try count: 100 for all tests (context selection is critical routing decision)
  - Assertion pattern: Check isSuccess(), getStatus() for all context types

### Fallback Mechanism Pattern
- **Class**: `FallbackMechanismTest.java`
- **Purpose**: Validates fallback strategies for unavailable agents and routing failures
- **Request Types**: `"context-fallback"`, `"domain-fallback"`, `"universal-fallback"`, `"agent-failure-fallback"`
- **SecurityContext**: `serviceType("property")`, `serviceId("pos-fallback-tests")`
- **Key Pattern**:
  - Test context-based fallback when primary agent unavailable
  - Validate domain-based fallback for known domains
  - Test universal fallback to architecture/implementation/documentation agents
  - Verify agent failure fallback to related agents
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> agentFailureScenarios() {
      return Arbitraries.of(
              "implementation:architecture",
              "security:implementation",
              "deployment:architecture",
              "testing:implementation",
              "event-driven:architecture",
              "cicd:deployment",
              "configuration:implementation",
              "resilience:architecture",
              "unknown:none"
      ).map(scenario -> {
          String[] parts = scenario.split(":");
          return AgentContext.builder()
                  .domain("agent-failure-fallback")
                  .property("failedAgent", parts[0])
                  .property("fallbackAgent", parts[1])
                  .property("hasFallback", !"none".equals(parts[1]))
                  .build();
      });
  }
  ```
- **Test Pattern**:
  1. **contextBasedFallback()** - Tests fallback based on request context
  2. **domainBasedFallback()** - Validates fallback for known domains (spring-boot, security)
  3. **universalFallback()** - Tests universal fallback agents (architecture, implementation, documentation)
  4. **agentFailureFallback()** - Tests fallback when specific agent fails
- **Special Considerations**:
  - Fallback hierarchy: context → domain → universal → none
  - Agent failure fallback mappings: implementation→architecture, security→implementation, etc.
  - Use requiresFallback and hasFallback boolean flags to track fallback scenarios
  - Try count: 100 for all tests (fallback is critical reliability mechanism)
  - Assertion pattern: Check isSuccess(), getStatus() for all fallback scenarios

### Intelligent Agent Routing Pattern
- **Class**: `IntelligentAgentRouterTest.java`
- **Purpose**: Validates intelligent routing with service mapping, context analysis, and fallback strategies
- **Request Types**: `"service-mapping-route"`, `"context-fallback-route"`, `"universal-fallback-route"`, `"service-specific-route"`, `"domain-based-route"`
- **SecurityContext**: `serviceType("property")`, `serviceId("pos-router-tests")`
- **Key Pattern**:
  - Test routing with explicit service mapping (pos-inventory → implementation)
  - Validate context-based fallback routing when no service mapping exists
  - Test universal fallback for unknown requests
  - Verify service-specific routing with primary and suggested agents
  - Validate domain-based routing (spring-boot → implementation, security → security)
- **Generator Pattern**:
  ```java
  @Provide
  Arbitrary<AgentContext> serviceMappingScenarios() {
      return Arbitraries.of(
              "pos-inventory:implementation:Update inventory",
              "pos-catalog:business:Catalog management"
      ).map(scenario -> {
          String[] parts = scenario.split(":");
          return AgentContext.builder()
                  .domain("service-mapping")
                  .property("targetService", parts[0])
                  .property("primaryAgent", parts[1])
                  .property("description", parts[2])
                  .property("routingReason", "Primary agent mapping")
                  .build();
      });
  }
  
  @Provide
  Arbitrary<AgentContext> universalFallbackScenarios() {
      return Arbitraries.of(
              "unknown:Unknown request:architecture:Fallback mechanism",
              "unknown:Unknown type:none:No available agent"
      ).map(scenario -> {
          String[] parts = scenario.split(":");
          boolean hasAgent = !"none".equals(parts[2]);
          return AgentContext.builder()
                  .domain("universal-fallback")
                  .property("requestType", parts[0])
                  .property("description", parts[1])
                  .property("fallbackAgent", parts[2])
                  .property("routingReason", parts[3])
                  .property("hasAvailableAgent", hasAgent)
                  .build();
      });
  }
  ```
- **Test Pattern**:
  1. **routingWithServiceMapping()** - Tests direct service-to-agent mapping
  2. **routingWithContextFallback()** - Validates context-based fallback routing
  3. **routingWithUniversalFallback()** - Tests universal fallback for unknown requests
  4. **routingToSpecificServices()** - Tests routing to specific POS services
  5. **domainBasedRouting()** - Validates domain-based routing decisions
- **Special Considerations**:
  - Service mapping priority: explicit mapping → context-based → universal fallback
  - Routing reasons tracked: "Primary agent mapping", "Context-based selection", "Fallback mechanism"
  - Use hasAvailableAgent boolean flag to track agent availability
  - Service targets: pos-inventory, pos-catalog, pos-order
  - Domain mappings: spring-boot→implementation, security→security
  - Try count: 100 for all tests (routing decisions critical for request processing)
  - Assertion pattern: Check isSuccess(), getStatus() for all routing scenarios

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
Different tests use different `serviceType` values:
- `"manual"` - for manual testing
- `"debug"` - for debug tests
- `"test"` - for property tests
- `"property"` - for property-based tests
- `"collaboration"` - for collaboration tests

**ServiceId Patterns**:
- `"pos-testing-suite"` - testing domain
- `"pos-debug-tests"` - debug operations
- `"pos-impl-tests"` - implementation tests
- `"pos-security-tests"` - security tests
- `"pos-resilience-tests"` - resilience tests
- `"pos-domain-tests"` - domain tests

---

## AgentContext Property Patterns

### Universal Properties
These appear across multiple test types:
```java
.property("service", "pos-<domain>")           // Service being tested
.property("domain", "<domain>")                  // Logical domain
.property("complexity", "high"|"medium"|"low")  // Complexity level
```

### Domain-Specific Properties
**Testing Domain**:
- `service`: microservice name
- `topic`: "tdd", "property-based-testing"

**Collaboration Domain**:
- `scenario`: descriptive context
- `requestType`: "loop-detection", "architectural-drift"

**Event-Driven Domain**:
- `eventType`: type of event scenario
- `brokerType`: message broker type
- `processingType`: "idempotent", "eventually-consistent"

**Documentation Domain**:
- `documentationType`: "api", "technical", "architectural"
- `synchronizationType`: "code-first", "docs-first"

---

## Request Flow Pattern (Consistent Across All Tests)

```
1. Initialization Phase:
   └─ Create AgentManager (once per test class)
   └─ Create SecurityContext (once per test class)
   └─ Create SecurityContext specific to test if needed

2. Per-Test Execution:
   ├─ Generate/Create AgentContext with domain and properties
   ├─ Create AgentRequest:
   │  ├─ Set type (matches domain or specific agent type)
   │  ├─ Set context (AgentContext)
   │  └─ Set securityContext (SecurityContext)
   │
   ├─ Process Request:
   │  └─ agentManager.processRequest(agentRequest)
   │
   └─ Validate Response:
      ├─ Assert response.isSuccess() == true
      ├─ Assert response.getStatus() != null
      └─ Domain-specific assertions on response content

3. Per-Property Test (jqwik):
   └─ Steps 2a-2b repeat for each generated variation
   └─ All variations must satisfy assertions
```

---

## Key Validation Points Across All Tests

### Universal Validations
```java
// Every test validates these minimum requirements:
assertTrue(response.isSuccess());           // Request processed without error
assertNotNull(response.getStatus());        // Status is provided
assertNotNull(response.getProcessingTimeMs()); // Timing info available (where applicable)
```

### Performance Validations (Some Tests)
```java
// For tests with performance requirements:
assertTrue(response.getProcessingTimeMs() < THRESHOLD_MS);
```

### Content Validations (Domain-Specific)
```java
// Validate response contains domain-appropriate guidance:
assertThat(response.getStatus()).contains("architectural");
assertThat(response.getStatus()).contains("implementation");
// etc.
```

---

## Test Class Field Organization

All repaired tests follow this consistent field organization:

```java
public class ExamplePropertyTest {
    
    // 1. Shared Manager
    private final AgentManager agentManager = new AgentManager();
    
    // 2. Shared SecurityContext
    private final SecurityContext security = SecurityContext.builder()
        .userId("...")
        .roles(List.of(...))
        .permissions(List.of(...))
        .serviceId("...")
        .serviceType("...")
        .build();
    
    // 3. Optional: Setup method
    @BeforeEach
    void setUp() {
        // Optional initialization
    }
    
    // 4. Test methods (property or regular)
    @Property(tries = 100)
    void testMethod(@ForAll("generator") AgentContext context) { }
    
    // 5. Provider methods for generators
    @Provide
    Arbitrary<AgentContext> generator() { }
}
```

---

## Generator Patterns Used in Providers

### Pattern 1: String-to-AgentContext Mapping
```java
@Provide
Arbitrary<AgentContext> contexts() {
    return Arbitraries.of("option1", "option2", "option3")
        .map(option -> AgentContext.builder()
            .domain("domain")
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
        .domain("domain")
        .property("service", service)
        .property("topic", topic)
        .build());
}
```

### Pattern 3: Filtered Sets
```java
@Provide
Arbitrary<List<String>> agentTypes() {
    List<String> all = List.of("type1", "type2", "type3", "type4", "type5");
    return Arbitraries.integers().between(4, 6)
        .flatMap(count -> Arbitraries.shuffle(all)
            .map(shuffled -> shuffled.subList(0, count)));
}
```

### Pattern 4: AgentRequest Directly
Some tests generate `AgentRequest` instead of context:
```java
@Provide
Arbitrary<AgentRequest> requests() {
    return Arbitraries.of("service1", "service2")
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

## Special Cases & Variations

### Case 1: Direct Agent Usage
**File**: `EventSchemaConsistencyPropertyTest.java`
- Imports and uses `EventDrivenArchitectureAgent` directly
- More specialized than core API approach
- Suggests some agents may have specialized APIs

```java
private EventDrivenArchitectureAgent eventDrivenAgent;

@BeforeEach
void setUp() {
    eventDrivenAgent = new EventDrivenArchitectureAgent();
}
```

### Case 2: Assumptions in Tests
**File**: `CrossAgentCollaborationPropertyTest.java`
- Uses `Assume.that()` to filter test variations
- Only runs tests when preconditions are met

```java
@Property(tries = 100)
void test(@ForAll("data") List<String> data) {
    Assume.that(data.size() >= 4);  // Only run if size >= 4
    // test code
}
```

### Case 3: Multiple Setup Methods
**File**: `DomainBoundaryEnforcementPropertyTest.java`
- Has both `@BeforeEach setUp()` and `ensureSetup()` helper
- Lazy initialization pattern for optional setup

```java
@BeforeEach
void setUp() { /* ... */ }

private void ensureSetup() {
    if (agentManager == null) {
        agentManager = new AgentManager();
    }
}
```

### Case 4: Multiple Property Tests in Single Class
**Files**: `ApiGatewayIntegrationConsistencyPropertyTest.java`, `CICDSecurityIntegrationPropertyTest.java`, `ConfigurationManagementConsistencyPropertyTest.java`
- Single `AgentManager` and `SecurityContext` shared across all test methods
- Each `@Property` test validates one specific behavior/aspect
- Each test has corresponding `@Provide` generator (or shared generators)
- Request types vary by test focus (e.g., "api-gateway", "api-versioning", "rest-api-design")
- Response validation consistent across all tests: `isSuccess()`, `getStatus()`, `getProcessingTimeMs()`

**Pattern**:
```java
class MultiPropertyTestClass {
    private final AgentManager agentManager = new AgentManager();
    private final SecurityContext security = /* ... */;
    
    @Property(tries = 100)
    void testAspectOne(@ForAll("contextsOne") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
            .type("type-one")
            .context(context)
            .securityContext(security)
            .build();
        AgentResponse response = agentManager.processRequest(request);
        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }
    
    @Property(tries = 100)
    void testAspectTwo(@ForAll("contextsTwo") AgentContext context) {
        AgentRequest request = AgentRequest.builder()
            .type("type-two")
            .context(context)
            .securityContext(security)
            .build();
        AgentResponse response = agentManager.processRequest(request);
        assertTrue(response.isSuccess());
        assertNotNull(response.getStatus());
    }
    
    @Provide
    Arbitrary<AgentContext> contextsOne() { /* ... */ }
    
    @Provide
    Arbitrary<AgentContext> contextsTwo() { /* ... */ }
}
```

**Benefits**:
- Reduces test class boilerplate (one manager/context per class)
- Validates multiple related behaviors in single focused test class
- Enables shared generator logic
- Improves test clarity (one test per behavior)

---

## Key Takeaways for Repairing Other Tests

### 1. Always Use Core API Pattern
- Use `AgentManager.processRequest(AgentRequest)`
- Don't bypass with direct agent instantiation (except special cases)
- Build requests with fluent builders

### 2. Security Context Setup
- Create once per test class as final field
- Use consistent userId, roles, permissions pattern
- Match serviceType and serviceId to test purpose

### 3. Context Properties Are Domain-Specific
- Domain field is mandatory
- Properties map varies by domain
- Align property names with agent expectations
- Use proper service names (pos-*)

### 4. Response Validation Pattern
- Always validate `isSuccess()`
- Always validate `getStatus()` is not null
- Add domain-specific validations after basic checks
- Use AssertJ's `assertThat()` for complex validations

### 5. Property-Based Test Structure
- Use jqwik `@Property` annotation
- Provide generators via `@Provide` methods
- Use `@ForAll` to inject generated data
- Use `Assume.that()` to filter invalid variations
- Iterate 100+ times for confidence

### 6. Generator Patterns
- Map simple strings to complex objects
- Use Cartesian products for combinations
- Filter and shuffle for complex scenarios
- Generate from realistic domain values (service names, topics, scenarios)

---

## Summary Table: Test Coverage by Domain

| Domain | Test Classes | Request Types | Key Properties | Agent Types |
|--------|-------------|---------------|----------------|-------------|
| Testing | TestingAgentManualTest | testing | service, topic | Testing Agent |
| Collaboration | PairProgrammingNavigatorDebugTest, PairProgrammingLoopDetectionPropertyTest | collaboration | scenario, requestType | Pair Programming Agent |
| Security | SecurityComplianceValidationPropertyTest | security | vulnerability, scenario | Security Agent |
| Resilience | ResiliencePatternEffectivenessPropertyTest | resilience | pattern-type | Resilience Engineer Agent |
| Business/POS | POSDomainPatternAdherencePropertyTest | business | service, domain | POS Domain Agent |
| Implementation | Multiple tests | implementation | service, pattern | Implementation Agent |
| Event-Driven | EventSchemaConsistencyPropertyTest | event-driven | eventType, brokerType | Event-Driven Architecture Agent |
| Documentation | DocumentationSynchronizationPropertyTest | documentation | documentationType | Documentation Agent |
| Observability | ObservabilityInstrumentationCompletenessPropertyTest | observability | instrumentationType | Observability Agent |
| Data Access | DataStoreGuidanceAppropriatenessPropertyTest | implementation | dataStoreType | Data Store Guidance Agent |
| Cross-Agent | CrossAgentCollaborationPropertyTest | multi-type | complexity, requiredAgents | Multiple collaborating agents |
| Domain Boundaries | DomainBoundaryEnforcementPropertyTest | architecture | domain, service | Domain Boundary Agent |
| Service Boundaries | ServiceBoundaryValidationPropertyTest | business | service, boundary-type | Service Boundary Agent |
| Spring Boot Patterns | SpringBootPatternProvisionPropertyTest | implementation | service, framework | Spring Boot Pattern Agent |
| Domain-Specific Guidance | DomainSpecificGuidanceQualityPropertyTest | multi-domain | domain, service, topic | Domain-specific agents |

---

## Architecture Diagram: Request Processing Flow

```
Test Class
  ├── Fields:
  │   ├── AgentManager agentManager (shared, reused)
  │   └── SecurityContext security (shared, reused)
  │
  ├── Setup (Optional):
  │   └── @BeforeEach setUp()
  │
  └── Test Methods:
      ├── Manual @Test or Property @Property(tries=N)
      │
      ├── For each test iteration:
      │   │
      │   ├── Generate/Create AgentContext
      │   │   ├── Set domain value
      │   │   └── Set properties (domain-specific)
      │   │
      │   ├── Create AgentRequest
      │   │   ├── .type("...") 
      │   │   ├── .context(agentContext)
      │   │   ├── .securityContext(security)
      │   │   └── .build()
      │   │
      │   ├── Process: agentManager.processRequest(request)
      │   │   └── Returns: AgentResponse
      │   │
      │   └── Validate Response:
      │       ├── response.isSuccess() ✓
      │       ├── response.getStatus() ✓
      │       └── Domain-specific checks ✓
      │
      └── Providers (@Provide):
          ├── @Provide Arbitrary<AgentContext> ...()
          ├── @Provide Arbitrary<String> ...()
          └── @Provide Arbitrary<List<String>> ...()
```

