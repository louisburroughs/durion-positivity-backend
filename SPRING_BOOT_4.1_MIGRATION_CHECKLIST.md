# Spring Boot 4.1 Migration - Comprehensive Implementation Checklist

**Project:** durion-positivity-backend  
**Migration Target:** Spring Boot 3.4.2 → 4.1.0+  
**Start Date:** January 30, 2026  
**Java Version:** Stay on Java 21 (LTS)  
**Jackson:** Full migration to Jackson 3.0 (no compatibility mode)  
**Spring Cloud:** Test with Spring Cloud 2025.0.0  
**Testing Strategy:** Full comprehensive testing (integration, contract, API gateway routing)

---

## PRE-IMPLEMENTATION VALIDATION

### Environment Setup
- [ ] Git branch created: `migration/spring-boot-4.1-jackson3-sc2025`
- [ ] Current state backed up and all tests passing on Spring Boot 3.4.2
- [ ] Java 21 installed and JAVA_HOME set correctly
- [ ] Maven wrapper (./mvnw) verified working
- [ ] IDE configured for Java 21 with null analysis enabled
- [ ] Sufficient disk space available (~5GB for builds)
- [ ] Current CVE dependency check clean: `./mvnw org.owasp:dependency-check-maven:check`

### Documentation Review
- [ ] Read Spring Boot 4.1 Release Notes (breaking changes section)
- [ ] Read Spring Framework 7.0 Migration Guide
- [ ] Read Spring Security 7.0 Release Notes
- [ ] Read Spring Cloud 2025.0.0 Release Notes
- [ ] Read Jackson 3.0 Migration Guide

---

## PHASE 1: DEPENDENCY AND BUILD CONFIGURATION UPDATES

### Root POM.xml Updates
- [ ] **Task 1.1:** Update `<parent>` from `spring-boot-starter-parent:3.4.2` to `4.1.0` (or latest 4.0.x/4.1.x)
  - File: `/pom.xml` (root)
  - Verify: Parent POM resolves without errors
  
- [ ] **Task 1.2:** Update `spring-cloud-dependencies` from `2024.0.0` to `2025.0.0`
  - File: `/pom.xml` (root)
  - Update `<spring-cloud.version>` property
  
- [ ] **Task 1.3:** Add/Update Jackson 3.0 dependency management
  - Replace: `com.fasterxml.jackson.core:jackson-databind` with `tools.jackson.core:jackson-databind` (Jackson 3.0+)
  - Update all Jackson artifacts to use `tools.jackson.*` group ID
  - Jackson 3.0 versions (e.g., `3.0.0`)
  
- [ ] **Task 1.4:** Update Hibernate from 6.x to 7.1+
  - Update `org.hibernate.orm:hibernate-core` version
  - Update `hibernate-jpamodelgen` to `hibernate-processor` (Hibernate 7.1+)
  
- [ ] **Task 1.5:** Verify Servlet 6.1 / Tomcat 11.0+ compliance
  - Confirm Spring Boot 4.1 includes Tomcat 11.0+
  - Verify no explicit Tomcat version override needed
  
- [ ] **Task 1.6:** Update Spring Data versions (if explicitly managed)
  - Spring Data JPA 2025.1.x+ (managed by Spring Boot 4.1)
  - Verify no version conflicts

### pos-dependencies (Internal BOM) Updates
- [ ] **Task 1.7:** Update `pos-dependencies/pom.xml`
  - Ensure it uses correct Spring Boot 4.1 managed versions
  - Add Jackson 3.0 artifact management if custom DTOs exist
  - Verify no transitive dependency conflicts

### Module POM.xml Validation
- [ ] **Task 1.8:** Verify all 26 pos-* modules inherit from parent correctly
  - Spot-check 3-5 modules for correct parent reference
  - Ensure no modules override Spring Boot version

### Dependency Conflict Resolution
- [ ] **Task 1.9:** Run dependency tree analysis
  - Command: `./mvnw dependency:tree > /tmp/dep-tree.txt`
  - Scan for version conflicts (RED flags in output)
  - Resolve all convergence issues

- [ ] **Task 1.10:** Run initial build compilation test
  - Command: `./mvnw clean compile -X` (with debug flag for verbose errors)
  - Verify no compilation errors in root or modules
  - **Gate Check:** All modules compile without errors before proceeding

### CI/CD Updates
- [ ] **Task 1.11:** Update `.github/workflows/ci.yml`
  - Confirm Java 21 (temurin) is specified
  - Verify no Java version override needed
  
- [ ] **Task 1.12:** Update `.github/workflows/pr-checks.yml`
  - Confirm Java 21 (temurin) is specified

---

## PHASE 2: JACKSON 3.0 MIGRATION - CODE REFACTORING

### Global Search and Replace Tasks
- [ ] **Task 2.1:** Find and replace Jackson annotations
  - Search: `@JsonComponent` → Replace: `@JacksonComponent`
  - Files affected: All `*Config.java` files with custom JSON components
  - Command: `grep -r "@JsonComponent" durion-positivity-backend/pos-*/`
  
- [ ] **Task 2.2:** Replace @JsonMixin
  - Search: `@JsonMixin` → Replace: `@JacksonMixin`
  - Command: `grep -r "@JsonMixin" durion-positivity-backend/pos-*/`

### Jackson Configuration Classes
- [ ] **Task 2.3:** Audit all custom `ObjectMapper` configurations
  - Files to check: `**/internal/config/*Config.java`
  - Verify Jackson 3.0 API usage (e.g., `ObjectMapper.findAndRegisterModules()` still works)
  - Check for deprecated Jackson 2 methods/classes
  
- [ ] **Task 2.4:** Update Jackson feature registration (if custom modules used)
  - Verify `mapper.registerModules()` patterns
  - Ensure no Jackson 2-specific modules imported

### DTO and Entity Classes
- [ ] **Task 2.5:** Scan all DTO classes for Jackson annotations
  - Search for: `@JsonProperty`, `@JsonInclude`, `@JsonIgnore`, etc.
  - Verify these are Jackson 3.0 compatible (no package name changes for these)
  - Command: `find durion-positivity-backend/pos-*/src -name "*DTO.java" -o -name "*Dto.java"`
  
- [ ] **Task 2.6:** Verify @JsonDeserialize and @JsonSerialize annotations
  - Check for custom deserializers/serializers
  - Ensure they inherit from Jackson 3.0 base classes
  
- [ ] **Task 2.7:** Check for Jackson 2 explicit imports
  - Search: `import com.fasterxml.jackson.*` → should be `import tools.jackson.*`
  - Command: `grep -r "com.fasterxml.jackson" durion-positivity-backend/pos-*/src/`
  - Replace all with `tools.jackson.*`

### REST Endpoint Serialization Testing
- [ ] **Task 2.8:** Test Jackson serialization on critical REST endpoints
  - Endpoints to test:
    - pos-accounting: `/rest/journal-entries` (GET/POST)
    - pos-order: `/rest/orders` (GET/POST)
    - pos-invoice: `/rest/invoices` (GET)
    - pos-catalog: `/rest/products` (GET)
  - Manual test or create simple integration test
  - Verify JSON output is correct (no malformed data)
  
- [ ] **Task 2.9:** Test Jackson deserialization (request body parsing)
  - POST/PUT endpoints with JSON payloads
  - Verify request validation errors are clear
  - Check for serialization errors in response

### Compilation Verification
- [ ] **Task 2.10:** Build and verify Jackson migration
  - Command: `./mvnw clean compile`
  - Verify no Jackson 2 references remain
  - **Gate Check:** All code compiles without Jackson errors before Phase 3

---

## PHASE 3: SPRING SECURITY 7.0 REFACTORING

### Security Configuration Audit
- [ ] **Task 3.1:** Audit pos-people SecurityConfig
  - File: `pos-people/src/main/java/.../internal/config/SecurityConfig.java`
  - Review: Method signature of `SecurityFilterChain` bean
  - Check: Any deprecated security patterns
  
- [ ] **Task 3.2:** Audit pos-customer SecurityConfig
  - File: `pos-customer/src/main/java/.../internal/config/SecurityConfig.java`
  - Same checks as 3.1
  
- [ ] **Task 3.3:** Audit pos-catalog SecurityConfig
  - File: `pos-catalog/src/main/java/.../internal/config/SecurityConfig.java`
  
- [ ] **Task 3.4:** Audit pos-price SecurityConfig
  - File: `pos-price/src/main/java/.../internal/config/SecurityConfig.java`
  
- [ ] **Task 3.5:** Audit pos-security-service SecurityConfig
  - File: `pos-security-service/src/main/java/.../internal/config/SecurityConfig.java`

### SecurityFilterChain Pattern Updates
- [ ] **Task 3.6:** Update @Bean SecurityFilterChain method signatures
  - Pattern (old): `public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception`
  - Pattern (new): Same, but may need `@Bean` + correct parameter types
  - Verify: Uses `http.csrf().disable()` or new `csrf()` pattern
  - Verify: Uses correct `authorizeHttpRequests()` pattern (Spring Security 7.0)

### Authorization Rule Updates
- [ ] **Task 3.7:** Review and update authorization rules
  - Search for: `antMatchers()` → should be `requestMatchers()` in Spring Security 7.0
  - Update all authorization patterns to Spring Security 7.0 syntax
  - Verify: No `access()` patterns using expression strings (deprecated)

### Test Security Configuration
- [ ] **Task 3.8:** Update @WithMockUser and @WithUserDetails annotations
  - Files: `*/src/test/java/**/*Tests.java`
  - Verify: Annotations still work (they should be compatible)
  - Update: Any custom test security config classes

- [ ] **Task 3.9:** Review Spring Security test support
  - Ensure `SecurityContextHolder` usage is correct
  - Verify: `with(csrf())` in test requests if needed

### Spring Security 7.0 Verification
- [ ] **Task 3.10:** Verify imports and class availability
  - Command: `./mvnw clean compile`
  - Verify no import errors from `org.springframework.security.*` packages
  - **Gate Check:** All security configs compile without errors before Phase 4

---

## PHASE 4: CUSTOM SPRING CONFIGURATION AND IMPORT UPDATES

### @SpringBootApplication Classes
- [ ] **Task 4.1-4.3:** Audit all 24 @SpringBootApplication classes
  - Scan each module for broken imports
  - Check for `BootstrapRegistry` usage → update imports
  - Check for `EnvironmentPostProcessor` usage → update imports
  - Files to check: `/src/main/java/com/positivity/{domain}/{Domain}Application.java`
  - Command: `find durion-positivity-backend/pos-*/src/main -name "*Application.java"`

### RestTemplate and REST Client Configuration
- [ ] **Task 4.4:** Review all RestTemplateConfig classes
  - Verify: `RestTemplateBuilder` still used or migrate to `RestClient`
  - Check: Spring Boot 4.1 deprecation notices for RestTemplate
  - Update: If migrating to RestClient (preferred in Spring Boot 4.1+)
  - Files: Search `grep -r "RestTemplate" durion-positivity-backend/pos-*/src/main/`

### Cache Configuration
- [ ] **Task 4.5:** Review all CacheConfig classes
  - Verify: Cache annotations (@Cacheable, @CacheEvict, etc.) work
  - Check: Any custom CacheManager configurations
  - Update: If Spring Cache 7.0 API changed (unlikely)

### OpenAPI Configuration
- [ ] **Task 4.6:** Review all OpenApiConfig classes
  - Verify: springdoc-openapi 2.7.0 is compatible with Spring Boot 4.1
  - Check: @ApiResponse, @Operation, @Parameter annotations work
  - If issues: May need to upgrade springdoc-openapi to latest 2.x

### DataSource and JPA Configuration
- [ ] **Task 4.7:** Review DataSource and JPA configs
  - Verify: HibernateProperties or Hibernate 7.1 compatibility
  - Check: JPA property mappings still work
  - Update: If Hibernate 7.1 requires new patterns

### Nullability Annotations
- [ ] **Task 4.8:** Audit nullability annotations
  - Search: `org.springframework.lang.Nullable` usage
  - Decision: Keep as-is OR migrate to JSpecify @Nullable
  - Recommendation: Keep `@NonNull` from Spring, migrate others to JSpecify if needed
  - Command: `grep -r "org.springframework.lang" durion-positivity-backend/pos-*/src/`

### Compilation Verification
- [ ] **Task 4.9:** Build and verify all configs
  - Command: `./mvnw clean compile`
  - Verify no import errors or missing classes
  
- [ ] **Task 4.10:** Static analysis scan
  - IntelliJ: Analyze → Run Inspection by Name → "Deprecated"
  - Eclipse: Search → Search → Regular Expression: "deprecated"
  - Fix all Spring 6→7 deprecation warnings
  - **Gate Check:** All configs compile and no unresolved deprecations before Phase 5

---

## PHASE 5: TEST INFRASTRUCTURE REFACTORING

### @SpringBootTest Annotation Updates
- [ ] **Task 5.1:** Update @SpringBootTest classes using MockMvc
  - Add: `@AutoConfigureMockMvc` to test classes that use `@Autowired MockMvc`
  - Files: `**/src/test/java/**/*ControllerTests.java`
  - Command: `grep -r "@SpringBootTest" durion-positivity-backend/pos-*/src/test/ | head -20`
  
- [ ] **Task 5.2:** Update @SpringBootTest classes using TestRestTemplate
  - Add: `@AutoConfigureTestRestTemplate` to test classes that use `@Autowired TestRestTemplate`
  - Files: `**/src/test/java/**/*IntegrationTests.java`

### Mockito Annotation Updates
- [ ] **Task 5.3:** Replace @Mock with MockitoExtension or @MockitoBean
  - Search: All `@Mock` annotations
  - Option A: Add `@ExtendWith(MockitoExtension.class)` to test class
  - Option B: Replace with `@MockitoBean` (Spring managed)
  - Command: `grep -r "@Mock" durion-positivity-backend/pos-*/src/test/ --include="*.java"`
  
- [ ] **Task 5.4:** Replace @Captor with MockitoExtension
  - Search: All `@Captor` annotations
  - Ensure MockitoExtension is present (via @ExtendWith or dependency)
  - Command: `grep -r "@Captor" durion-positivity-backend/pos-*/src/test/ --include="*.java"`
  
- [ ] **Task 5.5:** Replace @MockBean/@SpyBean with @MockitoBean/@MockitoSpyBean
  - Search: All `@MockBean` and `@SpyBean` in tests
  - Replace with `@MockitoBean` and `@MockitoSpyBean`
  - Keep existing `@SpyBean` if it's used for @Autowired beans (compatibility)

### Test Data and Fixtures
- [ ] **Task 5.6:** Update test fixtures for Jackson 3.0
  - Review: Any JSON test data files or JSON hardcoded in tests
  - Verify: Serialization/deserialization works with Jackson 3.0
  - Update: If any package names or class names changed
  
- [ ] **Task 5.7:** Review integration test data setup
  - Check: Database setup/teardown in @SpringBootTest classes
  - Verify: No Jackson-related deserialization in test data builders
  - Test: Sample data objects serialize/deserialize correctly

### OpenAPI/Contract Test Updates
- [ ] **Task 5.8:** Review OpenAPI contract tests (if present)
  - Files: `**/src/test/java/**/*ContractTests.java` or `*ApiTests.java`
  - Verify: springdoc-openapi generates correct contracts
  - Update: If schema generation changes with Spring Boot 4.1

### Module Test Validation
- [ ] **Task 5.9:** Run unit tests per module
  - Command: `./mvnw -pl pos-accounting -am test`
  - Command: `./mvnw -pl pos-order -am test`
  - Command: `./mvnw -pl pos-invoice -am test`
  - Repeat for each critical pos-* module
  - Fix any failing tests
  
- [ ] **Task 5.10:** Run all tests
  - Command: `./mvnw clean test` (from root)
  - Fix all test failures
  - **Gate Check:** All tests pass before Phase 6

---

## PHASE 6: SPRING CLOUD EUREKA AND SERVICE DISCOVERY TESTING

### Eureka Server Setup
- [ ] **Task 6.1:** Start Eureka server locally
  - Command: `cd durion-positivity-backend/pos-service-discovery && ./mvnw spring-boot:run`
  - Verify: Eureka dashboard accessible at `http://localhost:8761`
  - Expected: Dashboard loads without errors

### Service Registration Testing
- [ ] **Task 6.2:** Verify Eureka client registration (3 test services)
  - Start pos-accounting: `cd pos-accounting && ./mvnw spring-boot:run`
  - Start pos-order: `cd pos-order && ./mvnw spring-boot:run`
  - Start pos-api-gateway: `cd pos-api-gateway && ./mvnw spring-boot:run`
  - Check Eureka dashboard: All 3 services appear in "Instances currently registered with Eureka"
  - Verify: Status is "UP" (green)

### Cross-Service Communication
- [ ] **Task 6.3:** Test cross-service REST calls via gateway
  - Call: `http://localhost:8080/api/accounting/accounts` (through gateway)
  - Expected: Gateway routes to pos-accounting service
  - Verify: Response is from pos-accounting (check response header or body)
  
- [ ] **Task 6.4:** Test service-to-service discovery without gateway
  - Use RestTemplate/RestClient to call service by name
  - Example: `restTemplate.getForObject("http://pos-order/api/orders", List.class)`
  - Verify: Service name resolution works via Eureka

### Load Testing
- [ ] **Task 6.5:** Load test service registration cycles
  - Start/stop services multiple times (5+ cycles)
  - Monitor Eureka dashboard for correct registration/deregistration
  - Verify: No stale entries or stuck services

### Spring Cloud Configuration
- [ ] **Task 6.6:** Verify Spring Cloud 2025.0.0 APIs
  - Scan code for deprecated Spring Cloud methods
  - Update: If Spring Cloud 2025.0.0 changed API patterns
  - Command: `grep -r "spring.cloud" durion-positivity-backend/pos-*/src/main/resources/`

### Eureka Configuration
- [ ] **Task 6.7:** Update any custom Eureka configuration
  - Files: `**/internal/config/*Eureka*.java` or properties
  - Verify: Spring Cloud 2025.0.0 compatible
  - Test: Custom config loads correctly

### Dashboard Accessibility
- [ ] **Task 6.8:** Verify Eureka dashboard
  - Access: `http://localhost:8761`
  - Verify: Dashboard displays registered services
  - Test: Can view service details/metadata

### Breaking Changes Documentation
- [ ] **Task 6.9:** Document any breaking changes
  - If Spring Cloud 2025.0.0 has API changes, document them
  - Note: Any deprecated patterns that must be updated
  
- [ ] **Task 6.10:** Test circuit breaker patterns (if applicable)
  - If using Hystrix/Resilience4j, verify patterns still work
  - Test: Fallback mechanisms function correctly
  - **Gate Check:** All Eureka and service discovery tests pass before Phase 7

---

## PHASE 7: FULL INTEGRATION AND CONTRACT TESTING

### Full Maven Build
- [ ] **Task 7.1:** Run full Maven verification build
  - Command: `./mvnw clean verify` (from root)
  - Expected: All modules compile, tests pass, no errors
  - Fix: Any build failures

### Architecture Tests (ArchUnit)
- [ ] **Task 7.2:** Run ArchUnit tests
  - Command: `./mvnw test -pl pos-archunit` (or integrated in each module)
  - Verify: Internal package encapsulation enforced
  - Verify: No prohibited dependencies between modules
  - Verify: Layering rules (controllers → services → repos) enforced
  - Fix: Any architecture violations

### Module Integration Tests
- [ ] **Task 7.3:** Run accounting module integration tests
  - Command: `./mvnw -pl pos-accounting -am verify`
  - Verify: All tests pass
  
- [ ] **Task 7.4:** Run order module integration tests
  - Command: `./mvnw -pl pos-order -am verify`
  
- [ ] **Task 7.5:** Run invoice module integration tests
  - Command: `./mvnw -pl pos-invoice -am verify`
  
- [ ] **Task 7.6:** Run workorder module integration tests
  - Command: `./mvnw -pl pos-workorder -am verify`

### API Gateway Routing Tests
- [ ] **Task 7.7:** Test API gateway routing
  - Start all services and gateway
  - Test 5+ key routes through gateway:
    - `/api/accounting/*` → pos-accounting
    - `/api/orders/*` → pos-order
    - `/api/invoices/*` → pos-invoice
    - `/api/catalog/*` → pos-catalog
    - `/api/prices/*` → pos-price
  - Verify: Correct service receives request
  - Verify: Response is correct

### Event Publishing/Consumption
- [ ] **Task 7.8:** Test event system (pos-events)
  - Verify: @ApiEvent annotations work
  - Test: Events are published and received
  - Verify: Event registry configuration works
  - Command: `./mvnw -pl pos-events -am test`

### Message Broker Connectivity
- [ ] **Task 7.9:** Verify message broker (if used)
  - If using RabbitMQ/Kafka, test connectivity
  - Verify: Event publishing to broker works
  - Verify: Event consumption from broker works

### Integration Test Failures
- [ ] **Task 7.10:** Document and fix integration test failures
  - List all failures
  - Root cause analysis
  - Fix issues
  - Re-run tests until all pass
  - **Gate Check:** All integration tests pass before Phase 8

---

## PHASE 8: DATABASE AND DATA LAYER VALIDATION

### PostgreSQL Driver
- [ ] **Task 8.1:** Test PostgreSQL JDBC driver compatibility
  - Driver: postgresql-42.7.7 (or latest 42.7.x)
  - Test: Database connection from Spring Boot 4.1 application
  - Verify: No ClassNotFound or JDBC errors
  - Command: `./mvnw -pl pos-accounting spring-boot:run` (connect to Postgres)

### Hibernate 7.1 Entity Scanning
- [ ] **Task 8.2:** Verify Hibernate 7.1 entity model generation
  - Verify: `hibernate-processor` generates metamodel classes correctly
  - Check: No errors in build process
  - Verify: Entities are discoverable by Spring Data JPA

### JPA Entity Mapping
- [ ] **Task 8.3:** Test JPA entity scanning and mapping
  - Start application: `./mvnw -pl pos-accounting spring-boot:run`
  - Verify: No JPA entity mapping errors in logs
  - Verify: `@Entity` classes are loaded correctly
  - Verify: No missing `@Column` or invalid mappings

### Spring Data JPA Repositories
- [ ] **Task 8.4:** Verify Spring Data JPA repositories
  - Test: CRUD operations (Create, Read, Update, Delete)
  - Test: Custom query methods
  - Test: Pagination and sorting
  - Command: Write simple repository test

### Database Transactions
- [ ] **Task 8.5:** Test database transactions and rollback
  - Test: @Transactional annotations work
  - Test: Rollback on exceptions works
  - Test: Transaction isolation levels enforced
  - Command: `./mvnw -pl pos-accounting -am test` (with DB tests)

### H2 In-Memory Database
- [ ] **Task 8.6:** Test H2 in-memory database
  - Verify: Unit tests using H2 pass
  - Verify: No schema/version incompatibilities
  - Command: `./mvnw -pl pos-* test` (ensure H2 tests included)

### MongoDB Connectivity (if used)
- [ ] **Task 8.7:** Test MongoDB connectivity (if any modules use it)
  - If applicable: Test connection
  - Verify: Document serialization with Jackson 3.0 works
  - Verify: No schema incompatibilities

### Elasticsearch Compatibility (if used)
- [ ] **Task 8.8:** Test Elasticsearch connectivity (if used)
  - If applicable: Verify `RestClient` still works
  - Check: No API changes in Spring Data Elasticsearch

### Database Migrations (if used)
- [ ] **Task 8.9:** Test database migrations (Flyway/Liquibase)
  - If migrations present: Verify they run successfully
  - Verify: Schema matches expected state
  - Check: No migration failures

### Data Layer Documentation
- [ ] **Task 8.10:** Document any breaking changes
  - List: Hibernate 7.1 changes that affected code
  - List: Spring Data JPA API changes
  - List: Any custom JPA configurations needed
  - **Gate Check:** All database tests pass before Phase 9

---

## PHASE 9: OBSERVABILITY AND MONITORING VALIDATION

### OpenTelemetry Compatibility
- [ ] **Task 9.1:** Verify OpenTelemetry 1.40.0 with Spring Boot 4.1
  - Test: OpenTelemetry agent loads correctly
  - Test: Traces are exported to collector (if present)
  - Verify: No ClassNotFound or version conflicts
  - Command: Check logs for OpenTelemetry initialization

### Micrometer Integration
- [ ] **Task 9.2:** Test Micrometer 1.4.2 integration
  - Verify: Metrics are collected
  - Verify: Custom metrics register correctly
  - Test: Micrometer annotations work (@Timed, @Counted, etc.)

### Micrometer Tracing Bridge
- [ ] **Task 9.3:** Verify Micrometer Tracing Bridge OTEL 1.4.2
  - Test: Trace context is propagated (W3C traceparent)
  - Test: Spans are created for HTTP requests
  - Verify: Tracing bridge configuration works

### Actuator Endpoints
- [ ] **Task 9.4:** Test Actuator endpoints
  - Test: `/actuator/health` returns UP
  - Test: `/actuator/prometheus` exports metrics
  - Test: `/actuator/loggers` accessible
  - Test: `/actuator/env` shows configuration
  - Command: `curl http://localhost:8080/actuator/health`

### Trace Context Propagation
- [ ] **Task 9.5:** Verify W3C trace context propagation
  - Test: HTTP requests include `traceparent` header
  - Test: Trace ID flows from gateway to backend services
  - Test: Trace correlation works across services
  - Tool: Use Chrome DevTools or curl to inspect headers

### Metrics Collection
- [ ] **Task 9.6:** Test metrics collection and export
  - Verify: HTTP request metrics collected
  - Verify: Custom business metrics collected
  - Test: Prometheus scrape endpoint works
  - Command: `curl http://localhost:8080/actuator/prometheus | head -20`

### @ApiEvent Annotations
- [ ] **Task 9.7:** Verify @ApiEvent annotations
  - Test: Event logging works
  - Verify: Events are published correctly
  - Check: Event types are registered
  - Command: Check application logs for event entries

### Event Registry Configuration
- [ ] **Task 9.8:** Test event registry configuration
  - Verify: Event types are pre-registered on startup
  - Verify: @Configuration class initializes correctly
  - Check: No missing event type registrations

### Observability Documentation
- [ ] **Task 9.9:** Document observability integration changes
  - List: Any changes to metric/trace collection
  - List: New observability features available in Spring Boot 4.1
  - List: Any configuration changes needed

### OTEL Collector Testing (Optional)
- [ ] **Task 9.10:** Test with OpenTelemetry collector (if available)
  - Start local OTEL collector: `docker-compose -f docker/otel-collector-compose.yml up`
  - Set: `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`
  - Verify: Traces exported to collector
  - Verify: Traces appear in collector backend
  - **Gate Check:** All observability tests pass before Phase 10

---

## PHASE 10: LOAD TESTING AND PERFORMANCE VALIDATION

### Load Testing Environment
- [ ] **Task 10.1:** Identify 3-5 critical business workflows
  - Workflow 1: Create order → Generate invoice → Record payment
  - Workflow 2: Update inventory → Check price → Generate quote
  - Workflow 3: Search products → Add to cart → Checkout
  - Workflow 4: Workorder creation → Assignment → Completion
  - Workflow 5: Accounting journal entry → Reconciliation → Report

### Load Testing Tool Setup
- [ ] **Task 10.2:** Set up load testing environment
  - Tool: JMeter or Gatling (based on team preference)
  - Setup: Test scripts for each workflow
  - Configuration: 100 concurrent users, 5-minute ramp-up

### Baseline Metrics (Spring Boot 3.4.2)
- [ ] **Task 10.3:** Establish baseline performance metrics
  - Deploy Spring Boot 3.4.2 version (current)
  - Run load tests
  - Record: Response time (avg, p95, p99), throughput, error rate
  - Record: Memory usage, CPU usage, GC time
  - Save: Baseline report as reference

### Spring Boot 4.1 Load Tests
- [ ] **Task 10.4:** Run load tests on Spring Boot 4.1
  - Deploy Spring Boot 4.1 version (new)
  - Run same load tests with same parameters
  - Record: All metrics (response time, throughput, error rate, etc.)

### Performance Comparison
- [ ] **Task 10.5:** Compare response times and throughput
  - Calculate: % difference in response time (should be < +5%)
  - Calculate: % difference in throughput (should be > -5%)
  - Document: Any significant differences
  - Action: Investigate if > 5% regression

### Memory Usage Analysis
- [ ] **Task 10.6:** Verify memory usage under load
  - Compare: Heap memory usage (3.4.2 vs 4.1)
  - Compare: GC frequency and pause time
  - Verify: No memory leaks
  - Acceptable: < 10% increase in memory usage

### Performance Regressions
- [ ] **Task 10.7:** Identify and document regressions
  - If regression > 5%: Root cause analysis
  - Check: JVM settings, GC configuration, thread pools
  - Potential causes: Jackson 3.0 overhead, Spring Framework changes
  - Action: Profile with JProfiler or JFR (Java Flight Recorder)

### Garbage Collection Behavior
- [ ] **Task 10.8:** Validate garbage collection
  - Enable GC logging: `-Xlog:gc*:file=gc.log`
  - Analyze: GC frequency, pause time
  - Verify: No full GC storms under load
  - Compare: GC behavior between 3.4.2 and 4.1

### Performance Report
- [ ] **Task 10.9:** Document performance findings
  - Create: Load test report
  - Include: Baseline vs. Spring Boot 4.1 comparison
  - Include: Any regressions and their causes
  - Include: Recommendations for optimization (if needed)

### Performance Issue Resolution
- [ ] **Task 10.10:** Address any performance issues
  - If regressions found: Implement fixes
  - Potential fixes: Jackson caching, Spring configuration tuning, JVM tuning
  - Re-run load tests after fixes
  - **Gate Check:** Performance meets acceptable thresholds before Phase 11

---

## PHASE 11: DOCUMENTATION AND RELEASE PREPARATION

### README Updates
- [ ] **Task 11.1:** Update root README.md
  - Update: Spring Boot version (3.4.2 → 4.1.x)
  - Update: Java version (21 LTS)
  - Update: Jackson version (2.x → 3.0)
  - Update: Spring Cloud version (2024.0.0 → 2025.0.0)
  - Add: Migration notes or link to migration guide

### AGENTS.md Updates
- [ ] **Task 11.2:** Update AGENTS.md
  - Document: Spring Boot 4.1 requirements
  - Document: Java 21 requirement
  - Document: Jackson 3.0 implications
  - Add: Link to migration guide
  - Add: Note about testing strategy

### Breaking Changes Documentation
- [ ] **Task 11.3:** Document all breaking changes
  - Jackson 3.0 migration: @JsonComponent → @JacksonComponent
  - Spring Security 7.0: SecurityFilterChain patterns
  - Spring Framework 7.0: Removed APIs
  - Spring Cloud 2025.0.0: Service discovery changes
  - Any other changes encountered

### API Changes Documentation
- [ ] **Task 11.4:** Document API changes for frontend
  - List: Any REST API contract changes
  - List: Any response format changes (due to Jackson 3.0)
  - List: Any status code changes
  - Notify: durion-moqui-frontend team of changes

### Migration Guide
- [ ] **Task 11.5:** Create migration guide for developers
  - Title: "Spring Boot 3.4.2 → 4.1 Migration Guide"
  - Content:
    - Overview of changes
    - Step-by-step migration instructions
    - Common issues and solutions
    - Testing checklist
  - Location: `/SPRING_BOOT_4.1_MIGRATION_GUIDE.md`

### CI/CD Documentation
- [ ] **Task 11.6:** Update CI/CD documentation
  - Document: Java 21 build environment
  - Document: Maven wrapper usage
  - Document: Test suite requirements
  - Update: `.github/workflows/` comments if needed

### Security Updates
- [ ] **Task 11.7:** Update security policies (if changed)
  - Review: Spring Security 7.0 impact on security policies
  - Review: Any new security features to enable
  - Document: Updated security requirements

### Release Notes
- [ ] **Task 11.8:** Create release notes
  - Title: "durion-positivity-backend v4.1.0 Release Notes"
  - Content:
    - Spring Boot upgrade to 4.1
    - Jackson 3.0 migration
    - Spring Cloud 2025.0.0 update
    - Performance improvements/regressions
    - Breaking changes
    - Migration instructions for users
  - Location: `/RELEASE_NOTES_v4.1.0.md`

### Architecture Documentation
- [ ] **Task 11.9:** Update architecture documentation
  - Review: Any patterns that changed with Spring Boot 4.1
  - Update: Microservices architecture docs if needed
  - Update: Security architecture (Spring Security 7.0)
  - Update: Event architecture (if changed)

### Merge to Master
- [ ] **Task 11.10:** Prepare for merge to master branch
  - Verify: All changes documented
  - Verify: All tests passing
  - Verify: All checklist items complete
  - Create: Pull request with migration branch
  - Add: Description linking to migration guide
  - Request: Code review from team leads
  - **Gate Check:** All documentation complete and PR merged before deployment

---

## FINAL VALIDATION CHECKLIST

### Build Validation
- [ ] Root pom.xml compiles without errors
- [ ] All 26 modules compile without errors
- [ ] Full Maven build (`./mvnw clean verify`) passes
- [ ] No dependency conflicts in dependency tree
- [ ] No transitive dependency issues

### Code Quality
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Architecture tests (ArchUnit) pass
- [ ] Code analysis tools pass (if configured)
- [ ] No critical sonarqube issues

### Functional Testing
- [ ] All REST endpoints work correctly
- [ ] JSON serialization/deserialization works (Jackson 3.0)
- [ ] Authentication and authorization works
- [ ] Database connectivity and operations work
- [ ] Service discovery and registration works (Eureka)
- [ ] Event publishing and consumption works
- [ ] API gateway routing works correctly

### Performance Testing
- [ ] Load tests pass
- [ ] Performance regressions < 5%
- [ ] Memory usage acceptable
- [ ] Garbage collection behavior stable
- [ ] Throughput meets requirements

### Observability
- [ ] OpenTelemetry traces collected
- [ ] Micrometer metrics collected
- [ ] Actuator endpoints responding
- [ ] Trace context propagation working
- [ ] Event logging working

### Documentation
- [ ] README updated
- [ ] AGENTS.md updated
- [ ] Breaking changes documented
- [ ] Migration guide created
- [ ] Release notes created
- [ ] Architecture documentation updated

### Deployment Readiness
- [ ] CI/CD pipelines updated and tested
- [ ] Docker images build successfully (if applicable)
- [ ] All changes committed to branch
- [ ] Pull request created and reviewed
- [ ] Ready for merge to master

---

## Risk Assessment & Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Jackson 3.0 breaks serialization | High | High | Comprehensive REST testing in Phase 2; revert plan documented |
| Spring Cloud 2025.0 service discovery issues | Medium | High | Early Eureka testing in Phase 6; Spring Cloud docs reviewed |
| Spring Security 7.0 breaks auth | Medium | High | Security config audit in Phase 3; auth flow testing in Phase 7 |
| Performance regression > 5% | Low | Medium | Load testing baseline in Phase 10; JVM profiling if needed |
| Custom configs have import errors | Medium | Medium | Comprehensive scan in Phase 4; IDE analysis before compile |
| Database compatibility issues | Low | Medium | Early PostgreSQL testing in Phase 8; schema validation |

---

## Success Criteria (ALL MUST BE TRUE)

✅ All 26 modules compile without errors  
✅ All unit tests pass  
✅ All integration tests pass  
✅ Architecture tests (ArchUnit) pass  
✅ Eureka service discovery works correctly  
✅ No performance regressions > 5%  
✅ All REST endpoints serialize/deserialize correctly (Jackson 3.0)  
✅ Authentication and authorization working  
✅ Observability (OpenTelemetry, Micrometer) functional  
✅ Documentation updated  
✅ All changes merged to master  
✅ Ready for production deployment

---

## Timeline

**Total Estimated Duration:** 4-6 weeks

| Phase | Tasks | Duration | Status |
|-------|-------|----------|--------|
| Pre-Implementation | Environment setup | 1-2 days | ⏳ |
| Phase 1 | Dependency updates | 2-3 days | ⏳ |
| Phase 2 | Jackson 3.0 migration | 3-5 days | ⏳ |
| Phase 3 | Spring Security 7.0 | 2-3 days | ⏳ |
| Phase 4 | Custom config updates | 2-3 days | ⏳ |
| Phase 5 | Test infrastructure | 3-5 days | ⏳ |
| Phase 6 | Eureka/Service discovery | 2-3 days | ⏳ |
| Phase 7 | Integration testing | 2-3 days | ⏳ |
| Phase 8 | Database/Data layer | 1-2 days | ⏳ |
| Phase 9 | Observability | 1-2 days | ⏳ |
| Phase 10 | Load testing | 1-2 days | ⏳ |
| Phase 11 | Documentation | 1 day | ⏳ |

**Total:** 21-32 days (3-5 weeks) of focused work

---

**CHECKLIST STATUS:** ✅ Complete and Ready for Implementation

**Next Step:** User confirms readiness to proceed with Phase 1 implementation.
