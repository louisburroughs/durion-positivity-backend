# Critical Test Fixes Needed - pos-accounting

## 🔴 URGENT: All Integration Tests Are Currently FAILING

**Status:** 99 tests run, 60 errors, 1 failure (61% failure rate)  
**Root Cause:** MockMvc bean not available in Spring context  
**Impact:** Cannot validate any accounting functionality via integration tests

---

## Quick Fix Guide (2-4 hours)

### Step 1: Create Base Integration Test Class

Create `src/test/java/com/positivity/accounting/BaseIntegrationTest.java`:

```java
package com.positivity.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {
    
    @Autowired
    protected WebApplicationContext context;
    
    protected MockMvc mockMvc;
    
    @Autowired
    protected ObjectMapper objectMapper;
    
    @BeforeEach
    void baseSetup() {
        this.mockMvc = MockMvcBuilders
            .webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }
}
```

### Step 2: Update All Contract Behavior Tests

For **each** of these files, make these changes:

**Files to update:**
- `APPaymentContractBehaviorIT.java`
- `ContractBehaviorIT.java`
- `CreditMemoContractBehaviorIT.java`
- `EventIngestionContractBehaviorIT.java` ⚠️ Also remove `@AutoConfigureMockMvc`
- `FinancialReportingContractBehaviorIT.java`
- `JournalEntryContractBehaviorIT.java` ⚠️ Also remove `@AutoConfigureMockMvc`
- `PostingCategoryMappingKeyContractBehaviorIT.java`
- `SuspenseQueueContractBehaviorIT.java`
- `contract/GLAccountContractBehaviorIT.java`
- `contract/MappingKeyContractBehaviorIT.java`
- `contract/PostingCategoryContractBehaviorIT.java`

**Changes for each file:**

1. **Remove these annotations from the class:**
   ```java
   @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
   @ActiveProfiles("test")
   @AutoConfigureMockMvc  // ⚠️ Remove this if present
   ```

2. **Extend BaseIntegrationTest:**
   ```java
   public class APPaymentContractBehaviorIT extends BaseIntegrationTest {
   ```

3. **Remove these fields (already in base class):**
   ```java
   @Autowired
   private MockMvc mockMvc;  // ❌ Remove
   
   @Autowired
   private ObjectMapper objectMapper;  // ❌ Remove
   ```

4. **Remove import for @AutoConfigureMockMvc if present:**
   ```java
   // ❌ Remove this import:
   import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
   ```

### Step 3: Update PaymentApplicationControllerIntegrationTest

This test already has manual MockMvc setup, so just extend the base class:

**File:** `integration/PaymentApplicationControllerIntegrationTest.java`

```java
// Change from:
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentApplicationControllerIntegrationTest {

// To:
class PaymentApplicationControllerIntegrationTest extends BaseIntegrationTest {
```

Remove the manual MockMvc field (inherited from base).

### Step 4: Keep AccountingServiceIntegrationTest As-Is

**File:** `integration/AccountingServiceIntegrationTest.java`

This test has custom security setup in `@BeforeEach`, so keep it as-is or optionally extend the base class but override `baseSetup()`.

### Step 5: Verify Java 21

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Should show 21.0.10
```

### Step 6: Run Tests

```bash
cd pos-accounting
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
../mvnw clean test -Dtest="*IT"
```

**Expected Result:** All 99 tests should now PASS ✅

---

## After Fixing: Next Priority Tasks

### 1. Add Missing Controller Tests (HIGH PRIORITY)

**Missing:**
- ❌ `AuditTrailContractBehaviorIT` - Audit trail is critical for compliance
- ❌ `InvoicePaymentContractBehaviorIT` - Core accounting functionality

**Template for new tests:**

```java
package com.positivity.accounting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Audit Trail Backend Contract Behavioral Tests")
public class AuditTrailContractBehaviorIT extends BaseIntegrationTest {
    
    @Autowired
    private AuditTrailRepository auditTrailRepository;
    
    private static final String API_V1_AUDIT_TRAIL = "/v1/accounting/audit-trail";
    private static final String TEST_USER = "testuser";
    private static final String TEST_AUTHORITIES = "accounting:audit:view";
    
    @BeforeEach
    void setUp() {
        auditTrailRepository.deleteAll();
    }
    
    @AfterEach
    void tearDown() {
        auditTrailRepository.deleteAll();
    }
    
    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder
            .header("X-User", TEST_USER)
            .header("X-Authorities", TEST_AUTHORITIES);
    }
    
    @Test
    @DisplayName("CP-AT-001: List audit trail entries with pagination")
    void testListAuditTrailWithPagination() throws Exception {
        // Arrange: Create test audit entries
        // ...
        
        // Act & Assert
        mockMvc.perform(withAuth(get(API_V1_AUDIT_TRAIL))
            .param("page", "0")
            .param("size", "10"))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(5));
    }
    
    // Add more tests...
}
```

### 2. Enable Code Coverage Reporting

Add to `pom.xml`:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Run with coverage:
```bash
../mvnw clean test jacoco:report
open target/site/jacoco/index.html
```

### 3. Extract Test Utilities

Create `src/test/java/com/positivity/accounting/util/TestAuthHelper.java`:

```java
package com.positivity.accounting.util;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public class TestAuthHelper {
    
    public static final String DEFAULT_TEST_USER = "testuser";
    
    public static MockHttpServletRequestBuilder withAuth(
        MockHttpServletRequestBuilder builder,
        String... authorities
    ) {
        return builder
            .header("X-User", DEFAULT_TEST_USER)
            .header("X-Authorities", String.join(",", authorities));
    }
    
    public static MockHttpServletRequestBuilder withAuthUser(
        MockHttpServletRequestBuilder builder,
        String user,
        String... authorities
    ) {
        return builder
            .header("X-User", user)
            .header("X-Authorities", String.join(",", authorities));
    }
}
```

Then in tests:
```java
import static com.positivity.accounting.util.TestAuthHelper.withAuth;

mockMvc.perform(withAuth(post("/v1/accounting/gl-accounts"), 
    "accounting:coa:create"))
```

---

## Checklist for Each Fix

- [ ] Created `BaseIntegrationTest.java`
- [ ] Updated `APPaymentContractBehaviorIT.java`
- [ ] Updated `ContractBehaviorIT.java`
- [ ] Updated `CreditMemoContractBehaviorIT.java`
- [ ] Updated `EventIngestionContractBehaviorIT.java` (removed `@AutoConfigureMockMvc`)
- [ ] Updated `FinancialReportingContractBehaviorIT.java`
- [ ] Updated `JournalEntryContractBehaviorIT.java` (removed `@AutoConfigureMockMvc`)
- [ ] Updated `PostingCategoryMappingKeyContractBehaviorIT.java`
- [ ] Updated `SuspenseQueueContractBehaviorIT.java`
- [ ] Updated `contract/GLAccountContractBehaviorIT.java`
- [ ] Updated `contract/MappingKeyContractBehaviorIT.java`
- [ ] Updated `contract/PostingCategoryContractBehaviorIT.java`
- [ ] Updated `integration/PaymentApplicationControllerIntegrationTest.java`
- [ ] Verified Java 21 setup
- [ ] Ran all tests and confirmed they pass
- [ ] Created `AuditTrailContractBehaviorIT.java` (new)
- [ ] Created `InvoicePaymentContractBehaviorIT.java` (new)
- [ ] Added JaCoCo plugin to `pom.xml`
- [ ] Generated coverage report

---

## Common Issues After Fix

### Issue: Tests still fail with security errors

**Solution:** Verify `GatewayAuthoritiesFilter` is configured in test profile:
```yaml
# src/test/resources/application-test.yml
spring:
  security:
    filter:
      order: 1  # Ensure gateway filter runs first
```

### Issue: Database connection failures

**Solution:** Add TestContainers for PostgreSQL:
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

Then in `BaseIntegrationTest`:
```java
@Testcontainers
public abstract class BaseIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb");
}
```

---

## Questions?

Refer to:
- Full analysis: `INTEGRATION_TEST_COVERAGE_ANALYSIS.md`
- Contract testing guide: `CONTRACT_TESTING.md`
- Working example: `integration/AccountingServiceIntegrationTest.java`

