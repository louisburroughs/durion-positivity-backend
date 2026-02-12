# Integration Test Coverage Analysis - pos-accounting Module

**Analysis Date:** 2026-02-12  
**Analyzed By:** QA Software Engineer Agent  
**Java Version Required:** Java 21 (Temurin 21.0.10)  
**Current Java Version in CI:** Java 17 (needs upgrade)

---

## Executive Summary

The pos-accounting module has **good integration test coverage** with **11 out of 13 controllers** having dedicated integration tests. However, there are **critical test infrastructure issues** preventing tests from running:

### 🔴 Critical Issues
1. **MockMvc Configuration Problem** - Tests fail with "No qualifying bean of type 'org.springframework.test.web.servlet.MockMvc'"
2. **Spring Boot 4.0 Compatibility** - Two test files use `@AutoConfigureMockMvc` which doesn't exist in Spring Boot 4.0.x
3. **Java Version Mismatch** - Project requires Java 21, but CI uses Java 17

### ✅ Strengths
- Comprehensive contract behavior testing following industry best practices
- Well-organized test structure with clear naming conventions
- Good coverage of happy path, validation errors, idempotency, and concurrency scenarios
- Excellent documentation (CONTRACT_TESTING.md)

---

## 1. Controller Integration Test Coverage

| **Controller** | **Has Test** | **Test File(s)** | **Test Count** | **Coverage** |
|---|:---:|---|:---:|---|
| **APPaymentController** | ✅ | `APPaymentContractBehaviorIT` | 11 | Execute payments, allocations, status, GL posting |
| **AccountingController** | ✅ | `AccountingServiceIntegrationTest` | ~15 | General accounting endpoints (GL, JE, posting) |
| **AuditTrailController** | ❌ | — | 0 | **Missing** |
| **CreditMemoController** | ✅ | `CreditMemoContractBehaviorIT` | ~8 | Create, apply, list, retrieve credit memos |
| **EventIngestionController** | ✅ | `EventIngestionContractBehaviorIT` | 15+ | Event submission, retrieval, retry, reprocessing |
| **FinancialReportingController** | ✅ | `FinancialReportingContractBehaviorIT` | 9 | Financial reports generation |
| **GLAccountController** | ✅ | `GLAccountContractBehaviorIT` | ~10 | Create, retrieve, update, list GL accounts |
| **InvoicePaymentController** | ❌ | — | 0 | **Missing** |
| **JournalEntryController** | ✅ | `JournalEntryContractBehaviorIT` | ~12 | Create, post, reverse journal entries |
| **MappingKeyController** | ✅ | `MappingKeyContractBehaviorIT` | ~6 | Create, retrieve, update mapping keys |
| **PaymentApplicationController** | ✅ | `PaymentApplicationControllerIntegrationTest` | ~8 | Apply/reverse payments, allocations |
| **PostingCategoryController** | ✅ | `PostingCategoryContractBehaviorIT` | ~7 | Create, retrieve, update posting categories |
| **PostingRuleController** | ⚠️ | `PostingCategoryMappingKeyContractBehaviorIT` | 1 | Stub only (501 Not Implemented) |

**Coverage Summary:**
- ✅ **Fully Covered:** 10 controllers (77%)
- ⚠️ **Partially Covered:** 1 controller (8%) - PostingRuleController
- ❌ **Not Covered:** 2 controllers (15%) - AuditTrailController, InvoicePaymentController

**Total Test Files:** 13 integration test files  
**Estimated Total Test Methods:** ~99 (based on test run output)

---

## 2. Integration Test Patterns

### 2.1 Base Test Structure

All integration tests follow a consistent pattern derived from `ContractBehaviorIT`:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Controller Backend Contract Behavioral Tests")
public class ControllerContractBehaviorIT {
    
    @Autowired
    private MockMvc mockMvc; // ❌ Currently fails to inject
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private Repository repository; // Direct DB access
    
    // Gateway authentication headers
    private static final String TEST_USER = "testuser";
    private static final String TEST_AUTHORITIES = "accounting:action:permission";
}
```

### 2.2 Test Naming Conventions

Tests use **scenario-based prefixes**:

| Prefix | Meaning | Example |
|--------|---------|---------|
| **CP-*** | Contract Path / Happy Path | `testExecutePayment_AutomaticAllocation_Success` |
| **VE-*** | Validation Errors | `testCreateGLAccountInvalidCodeFormat` |
| **ERR-*** | Error Scenarios | `testExecutePayment_NegativeAmount_BadRequest` |
| **ID-*** | Idempotency | `testJournalEntryIdempotency` |
| **CC-*** | Concurrency Control | `testOptimisticLockingPreventsConflict` |
| **PA-*** | Payment Application | `testApplyPayment_SingleInvoice_Success` |

### 2.3 Authentication Pattern

All tests inject gateway headers to simulate API Gateway authentication:

```java
private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
    return builder
        .header("X-User", TEST_USER)
        .header("X-Authorities", TEST_AUTHORITIES);
}

// Usage:
mockMvc.perform(withAuth(post("/v1/accounting/gl-accounts"))
    .contentType(MediaType.APPLICATION_JSON)
    .content(objectMapper.writeValueAsString(request)))
```

### 2.4 Database Cleanup Pattern

Tests use **explicit cleanup** in `@BeforeEach` and `@AfterEach`:

```java
@BeforeEach
void setUp() {
    // Clean up test data (prevents stale data)
    repository.deleteAll();
    dependentRepository.deleteAll();
    
    // Setup test data
    testEntity = entityRepository.save(createTestEntity());
}

@AfterEach
void tearDown() {
    repository.deleteAll();
    dependentRepository.deleteAll();
}
```

### 2.5 Assertion Strategies

**Inline JSON Path Assertions:**
```java
.andExpect(status().isCreated())
.andExpect(jsonPath("$.paymentId").value(testPaymentId.toString()))
.andExpect(jsonPath("$.appliedAmount").value(500.00))
.andExpect(jsonPath("$.applications.length()").value(1))
```

**Post-Response Database Assertions:**
```java
assertThat(applications).hasSize(1);
assertThat(applications.get(0).getAppliedAmount()).isEqualByComparingTo("500.00");
```

**BigDecimal Comparisons (Critical for Monetary Values):**
```java
assertThat(payment.getUnappliedAmount())
    .isEqualByComparingTo(BigDecimal.ZERO); // NOT .isEqualTo()
```

### 2.6 Test Data Setup Helpers

Tests use **nested helper methods** for DRY:

```java
private void createGLAccount(String code, String name, AccountType type) throws Exception {
    GLAccountCreateRequest dto = createGLAccountRequest(code, name, type);
    createGLAccountDirect(dto);
}
```

### 2.7 Idempotency Testing Pattern

```java
@Test
void testExecutePayment_Idempotency_Success() throws Exception {
    // First request (201 Created)
    MvcResult result1 = mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
        .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andReturn();
    
    // Second request with SAME identifier (200 OK, not 201)
    MvcResult result2 = mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
        .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk()) // NOT isCreated()
        .andReturn();
    
    // Assert: No duplicate created
    assertThat(repository.findAll()).hasSize(1);
}
```

---

## 3. Test Failures and Issues

### 3.1 Critical: MockMvc Bean Not Found (ALL TESTS FAIL)

**Error:**
```
org.springframework.beans.factory.UnsatisfiedDependencyException: 
Error creating bean with name '...ContractBehaviorIT': 
Unsatisfied dependency expressed through field 'mockMvc': 
No qualifying bean of type 'org.springframework.test.web.servlet.MockMvc' available
```

**Affected Tests:** All 11 contract behavior tests (`*ContractBehaviorIT`)

**Root Cause:** Tests use `@Autowired MockMvc` without proper configuration

**Impact:** **99 tests, 60 errors, 1 failure** (61% failure rate)

### 3.2 Critical: @AutoConfigureMockMvc Not Found in Spring Boot 4.0

**Error:**
```
package org.springframework.boot.test.autoconfigure.web.servlet does not exist
cannot find symbol: class AutoConfigureMockMvc
```

**Affected Tests:**
- `EventIngestionContractBehaviorIT.java` (line 20, 52)
- `JournalEntryContractBehaviorIT.java` (line 21, 57)

**Root Cause:** `@AutoConfigureMockMvc` was removed or relocated in Spring Boot 4.0.x

**Impact:** Compilation failure, prevents all tests from running

### 3.3 Warning: Java Version Mismatch

**Project Requirement:** Java 21 (configured in `pom.xml`)
**Current Environment:** Java 17 (Temurin 17.0.18)

**Impact:** Tests may have runtime issues or unexpected behavior

---

## 4. Missing Integration Tests

### 4.1 AuditTrailController (HIGH PRIORITY)

**Endpoints Likely Missing Coverage:**
- `GET /v1/accounting/audit-trail` - List audit trail entries
- `GET /v1/accounting/audit-trail/{id}` - Get specific audit entry
- Filtering by entity type, date range, user

**Why It Matters:** Audit trail is critical for compliance and debugging

**Recommended Tests:**
```java
@SpringBootTest
@DisplayName("Audit Trail Contract Behavioral Tests")
class AuditTrailContractBehaviorIT {
    // CP-AT-001: List audit trail entries with pagination
    // CP-AT-002: Filter audit trail by entity type
    // CP-AT-003: Filter audit trail by date range
    // CP-AT-004: Filter audit trail by user
    // VE-AT-001: Invalid date range returns 400
}
```

### 4.2 InvoicePaymentController (HIGH PRIORITY)

**Endpoints Likely Missing Coverage:**
- `POST /v1/accounting/invoice-payments` - Record invoice payment
- `GET /v1/accounting/invoice-payments/{id}` - Get payment details
- `GET /v1/accounting/invoices/{invoiceId}/payments` - List payments for invoice

**Why It Matters:** Invoice payment is core accounting functionality

**Note:** There IS a `PaymentApplicationControllerIntegrationTest` which may partially cover this, but it's for PaymentApplicationController, not InvoicePaymentController

**Recommended Tests:**
```java
@SpringBootTest
@DisplayName("Invoice Payment Contract Behavioral Tests")
class InvoicePaymentContractBehaviorIT {
    // CP-IP-001: Record invoice payment with full amount
    // CP-IP-002: Record partial invoice payment
    // CP-IP-003: Apply payment to multiple invoices
    // CP-IP-004: List payments for invoice
    // VE-IP-001: Reject payment exceeding invoice balance
    // ID-IP-001: Idempotent payment recording
}
```

### 4.3 PostingRuleController (MEDIUM PRIORITY)

**Current State:** Stub implementation (returns 501 Not Implemented)

**Recommended Tests (when implemented):**
```java
@SpringBootTest
@DisplayName("Posting Rule Contract Behavioral Tests")
class PostingRuleContractBehaviorIT {
    // CP-PR-001: Create posting rule with valid conditions
    // CP-PR-002: Retrieve posting rule by ID
    // CP-PR-003: List posting rules for organization
    // CP-PR-004: Update posting rule
    // CP-PR-005: Activate/deactivate posting rule
    // VE-PR-001: Reject duplicate posting rule
    // VE-PR-002: Reject invalid GL account in rule
}
```

---

## 5. Test Infrastructure Issues

### 5.1 MockMvc Configuration Problem

**Current Pattern (BROKEN):**
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class ContractBehaviorIT {
    @Autowired
    private MockMvc mockMvc; // ❌ Fails to inject
}
```

**Working Pattern (from AccountingServiceIntegrationTest):**
```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountingServiceIntegrationTest {
    
    @Autowired
    private WebApplicationContext context;
    
    private MockMvc mockMvc; // NOT @Autowired
    
    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders
            .webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }
}
```

**Solution:** All `*ContractBehaviorIT` tests need to:
1. Remove `@Autowired` from `mockMvc` field
2. Inject `WebApplicationContext` instead
3. Build `MockMvc` manually in `@BeforeEach`

### 5.2 Spring Boot 4.0 Compatibility

**Problem:** `@AutoConfigureMockMvc` annotation not found

**Solution:** Remove `@AutoConfigureMockMvc` from:
- `EventIngestionContractBehaviorIT.java`
- `JournalEntryContractBehaviorIT.java`

And use the manual MockMvc setup pattern instead.

### 5.3 Java Version Setup

**Current CI Environment:**
```bash
$ java -version
openjdk version "17.0.18" 2026-01-20
```

**Required Version:**
```xml
<java.version>21</java.version>
```

**Solution:** Use Java 21 already installed in CI:
```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

---

## 6. Test Quality Metrics

### 6.1 Test Organization

| Category | Count | Notes |
|----------|-------|-------|
| **Contract Behavior Tests** | 11 | Follow consistent pattern |
| **Integration Tests** | 2 | In `integration/` subfolder |
| **Base Classes** | 1 | `ContractBehaviorIT` (general tests) |
| **Total Test Classes** | 13 | Well-organized |

### 6.2 Test Scenario Coverage

| Scenario Type | Coverage | Examples |
|---------------|----------|----------|
| **Happy Path (CP-*)** | ✅ Excellent | All controllers have CP tests |
| **Validation Errors (VE-*)** | ✅ Good | Invalid data, format errors |
| **Idempotency (ID-*)** | ✅ Good | Payment execution, journal entry creation |
| **Concurrency (CC-*)** | ⚠️ Limited | Only optimistic locking test |
| **Error Handling (ERR-*)** | ✅ Good | 404, 409, 400 error responses |
| **Pagination** | ⚠️ Limited | Some list endpoints |
| **Filtering** | ⚠️ Limited | Some list endpoints |

### 6.3 Test Code Quality

**Strengths:**
- ✅ Clear, descriptive test names with `@DisplayName`
- ✅ Consistent AAA (Arrange-Act-Assert) pattern
- ✅ Good use of helper methods for test data setup
- ✅ Proper cleanup in `@BeforeEach`/`@AfterEach`
- ✅ BigDecimal comparisons use `isEqualByComparingTo()` (correct for monetary values)
- ✅ Tests verify both HTTP response AND database state

**Areas for Improvement:**
- ⚠️ MockMvc setup duplicated across all test files (needs base class)
- ⚠️ Authentication headers duplicated (needs test utility class)
- ⚠️ Test data builders could be extracted to factories
- ⚠️ No parameterized tests (could reduce duplication)

---

## 7. Documentation Quality

### 7.1 Existing Documentation

✅ **CONTRACT_TESTING.md** - Excellent documentation including:
- Contract testing approach and philosophy
- Test scenario coverage table
- Running instructions
- Contract compliance checklist

✅ **Test File JavaDoc** - All test classes have comprehensive JavaDoc:
```java
/**
 * Contract Behavioral Integration Tests for AP Payments (CAP-053)
 *
 * This test suite validates the behavioral contracts defined in
 * durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md
 * Section: AP Payment endpoints (v1.0)
 */
```

### 7.2 Missing Documentation

❌ **Test Coverage Report** - No JaCoCo or similar coverage report generated  
❌ **Test Execution Guide** - No CI/CD integration documented  
❌ **Troubleshooting Guide** - No common test failure resolutions  

---

## 8. Recommendations

### 8.1 CRITICAL - Fix Test Infrastructure (Priority 1)

**Impact:** 99 tests currently fail

**Actions:**
1. **Create Base Test Class** to centralize MockMvc setup:
   ```java
   @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
   @ActiveProfiles("test")
   public abstract class BaseIntegrationTest {
       @Autowired
       protected WebApplicationContext context;
       
       protected MockMvc mockMvc;
       protected ObjectMapper objectMapper;
       
       @BeforeEach
       void baseSetup() {
           this.mockMvc = MockMvcBuilders
               .webAppContextSetup(context)
               .apply(springSecurity())
               .build();
           this.objectMapper = new ObjectMapper();
       }
   }
   ```

2. **Update All `*ContractBehaviorIT` Tests** to extend `BaseIntegrationTest`

3. **Remove `@AutoConfigureMockMvc`** from:
   - `EventIngestionContractBehaviorIT.java`
   - `JournalEntryContractBehaviorIT.java`

4. **Verify Java 21 in CI** - Add to CI pipeline:
   ```yaml
   - name: Set up Java 21
     uses: actions/setup-java@v3
     with:
       java-version: '21'
       distribution: 'temurin'
   ```

**Estimated Effort:** 2-4 hours

---

### 8.2 HIGH - Add Missing Controller Tests (Priority 2)

**1. Create `AuditTrailContractBehaviorIT`**
- Test audit trail listing with pagination
- Test filtering by entity type, date range, user
- Test audit trail entry retrieval by ID
- **Estimated Effort:** 4-6 hours

**2. Create `InvoicePaymentContractBehaviorIT`**
- Test invoice payment recording (full and partial)
- Test payment application to multiple invoices
- Test payment listing for invoice
- Test validation errors (overpayment, invalid invoice)
- **Estimated Effort:** 6-8 hours

**3. Enhance `PostingRuleContractBehaviorIT`** (when controller implemented)
- Test posting rule creation, retrieval, update
- Test rule activation/deactivation
- Test duplicate rule validation
- **Estimated Effort:** 6-8 hours (pending controller implementation)

**Total Estimated Effort:** 16-22 hours

---

### 8.3 MEDIUM - Improve Test Infrastructure (Priority 3)

**1. Extract Common Test Utilities**
```java
public class TestAuthenticationHelper {
    public static MockHttpServletRequestBuilder withAuth(
        MockHttpServletRequestBuilder builder, 
        String... authorities
    ) {
        return builder
            .header("X-User", "testuser")
            .header("X-Authorities", String.join(",", authorities));
    }
}
```

**2. Create Test Data Factories**
```java
public class TestDataFactory {
    public static GLAccount createTestGLAccount(String code, AccountType type) {
        // ...
    }
    
    public static JournalEntry createTestJournalEntry(BigDecimal amount) {
        // ...
    }
}
```

**3. Add Test Containers for PostgreSQL**
```java
@Testcontainers
class BaseIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb");
}
```

**4. Enable JaCoCo Code Coverage**
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Estimated Effort:** 8-12 hours

---

### 8.4 MEDIUM - Enhance Test Scenarios (Priority 4)

**1. Add More Concurrency Tests**
- Concurrent payment applications
- Concurrent journal entry posting
- Race conditions in balance updates

**2. Add Performance Tests**
- Bulk journal entry creation
- Large pagination results
- Complex GL account hierarchy queries

**3. Add Negative Scenario Tests**
- Network timeouts (with external service mocks)
- Database connection failures
- Invalid JWT tokens (currently mocked via headers)

**4. Add Parameterized Tests** to reduce duplication:
```java
@ParameterizedTest
@CsvSource({
    "CHECKING, true",
    "SAVINGS, true",
    "CREDIT_CARD, false"
})
void testAccountTypeValidation(AccountType type, boolean shouldSucceed) {
    // ...
}
```

**Estimated Effort:** 12-16 hours

---

### 8.5 LOW - Documentation Improvements (Priority 5)

**1. Add Test Coverage Badge** to README
**2. Create Troubleshooting Guide** with common test failures
**3. Document CI/CD Integration** with test execution
**4. Add Test Naming Convention Guide** for new contributors

**Estimated Effort:** 4-6 hours

---

## 9. Implementation Plan

### Phase 1: Fix Critical Infrastructure (Week 1)
- [ ] Create `BaseIntegrationTest` class with MockMvc setup
- [ ] Migrate all `*ContractBehaviorIT` tests to extend base class
- [ ] Remove `@AutoConfigureMockMvc` annotations
- [ ] Verify Java 21 in CI environment
- [ ] **Success Criteria:** All existing tests pass

### Phase 2: Add Missing Tests (Week 2-3)
- [ ] Create `AuditTrailContractBehaviorIT` with 8-10 tests
- [ ] Create `InvoicePaymentContractBehaviorIT` with 10-12 tests
- [ ] **Success Criteria:** 100% controller coverage

### Phase 3: Improve Test Infrastructure (Week 4)
- [ ] Extract `TestAuthenticationHelper` utility
- [ ] Create `TestDataFactory` for common entities
- [ ] Add TestContainers for PostgreSQL
- [ ] Enable JaCoCo code coverage reporting
- [ ] **Success Criteria:** Coverage report shows 80%+ service layer coverage

### Phase 4: Enhance Test Scenarios (Week 5-6)
- [ ] Add 5+ concurrency tests
- [ ] Add 3+ performance tests
- [ ] Add 10+ negative scenario tests
- [ ] Convert 20+ tests to parameterized tests
- [ ] **Success Criteria:** 120+ integration tests with broad scenario coverage

### Phase 5: Documentation (Week 7)
- [ ] Add coverage badge to README
- [ ] Create troubleshooting guide
- [ ] Document CI/CD integration
- [ ] Add test naming convention guide
- [ ] **Success Criteria:** Complete test documentation

**Total Estimated Timeline:** 7 weeks  
**Total Estimated Effort:** 40-56 hours

---

## 10. Related Test Files Reference

For test pattern examples, reference these well-structured tests:

1. **MockMvc Setup:** `AccountingServiceIntegrationTest.java` (manual setup)
2. **Contract Testing:** `APPaymentContractBehaviorIT.java` (11 tests, comprehensive)
3. **Idempotency Testing:** `APPaymentContractBehaviorIT.java` (CP-AP-003)
4. **Validation Testing:** `GLAccountContractBehaviorIT.java` (VE-GL-* tests)
5. **Error Handling:** `CreditMemoContractBehaviorIT.java` (ERR-* tests)

---

## 11. Key Contacts & Resources

- **Test Framework:** JUnit 5 (Jupiter), Spring Boot Test, MockMvc
- **Assertion Library:** AssertJ, Hamcrest (JSONPath)
- **Contract Guide:** `durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md`
- **Test Documentation:** `/pos-accounting/CONTRACT_TESTING.md`
- **Related Modules:** pos-events, pos-security-common, pos-shared-dtos

---

## Appendix A: Test Execution Commands

### Run All Integration Tests
```bash
cd pos-accounting
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
../mvnw test -Dtest="*IT"
```

### Run Specific Test Class
```bash
../mvnw test -Dtest=APPaymentContractBehaviorIT
```

### Run Specific Test Method
```bash
../mvnw test -Dtest=APPaymentContractBehaviorIT#testExecutePayment_AutomaticAllocation_Success
```

### Run Tests with Coverage
```bash
../mvnw clean test jacoco:report
# Report: target/site/jacoco/index.html
```

### Run Tests in Debug Mode
```bash
../mvnw test -Dtest=APPaymentContractBehaviorIT -Dmaven.surefire.debug
```

---

## Appendix B: Test Failure Examples

### Example 1: MockMvc Bean Not Found
```
[ERROR] com.positivity.accounting.APPaymentContractBehaviorIT.testExecutePayment_AutomaticAllocation_Success
org.springframework.beans.factory.UnsatisfiedDependencyException: 
Error creating bean with name 'com.positivity.accounting.APPaymentContractBehaviorIT': 
Unsatisfied dependency expressed through field 'mockMvc': 
No qualifying bean of type 'org.springframework.test.web.servlet.MockMvc' available: 
expected at least 1 bean which qualifies as autowire candidate. 
Dependency annotations: {@org.springframework.beans.factory.annotation.Autowired(required=true)}
```

**Solution:** Use manual MockMvc setup (see Section 5.1)

### Example 2: AutoConfigureMockMvc Not Found
```
[ERROR] /pos-accounting/src/test/java/com/positivity/accounting/EventIngestionContractBehaviorIT.java:[20,63] 
package org.springframework.boot.test.autoconfigure.web.servlet does not exist

[ERROR] cannot find symbol: class AutoConfigureMockMvc
```

**Solution:** Remove `@AutoConfigureMockMvc` and use manual setup

---

## Appendix C: Code Coverage Goals

| Layer | Current | Target | Gap |
|-------|---------|--------|-----|
| **Controllers** | Unknown | 90% | TBD |
| **Services** | Unknown | 85% | TBD |
| **Repositories** | Unknown | 70% | TBD |
| **DTOs** | Unknown | 50% | TBD |
| **Overall** | Unknown | 80% | TBD |

*Note: Coverage metrics require JaCoCo setup (see Recommendation 8.3)*

---

**End of Analysis**
