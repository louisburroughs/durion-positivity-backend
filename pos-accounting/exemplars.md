# POS Accounting — Code Exemplars

Purpose: identify high-quality, representative code examples in `pos-accounting` that demonstrate patterns and standards for controllers, services, repositories, entities, and tests.

## Table of Contents

- Presentation Layer (Controllers)
- Business Logic Layer (Services)
- Data Access Layer (Repositories)
- Domain Models (Entities)
- Tests (Integration/Contract)

---

## Presentation Layer (Controllers)

### 1. `JournalEntryController` (path: `internal/controller/JournalEntryController.java`)

- Why exemplary: Clear REST resource design, consistent authorization annotations, uses `PagedResponse` DTO and `JournalEntryMapper` for separation of concerns, emits events for observability.
- Pattern: Thin controller, delegate to service, map entities → DTOs.
- Snippet:

```java
@RestController
@RequestMapping("/v1/accounting/journal-entries")
public class JournalEntryController {
    @GetMapping
    @PreAuthorize("hasAuthority('accounting:je:view')")
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<JournalEntryResponse>> listJournalEntries(...) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));
        Page<JournalEntry> entryPage = journalEntryService.listJournalEntries(pageable);
        return ResponseEntity.ok(new PagedResponse<>(...));
    }
}
```

### 2. `FinancialReportingController` (path: `internal/controller/FinancialReportingController.java`)

- Why exemplary: Exposes focused reporting endpoints, consistent security (`reporting:view:financial-statements`), OpenAPI annotations, and `@EmitEvent` usage for audit/observability.
- Pattern: Domain-specific controller separated from CRUD controllers.
- Snippet:

```java
@RestController
@RequestMapping("/api/v1/reports/financial")
public class FinancialReportingController {
    @GetMapping("/income-statement")
    @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
    @EmitEvent(id = "REPORT_INCOME_STATEMENT_GENERATE", apiVersion = "1")
    public ResponseEntity<IncomeStatementReport> generateIncomeStatement(...) {
        if (endDate.isBefore(startDate)) throw new IllegalArgumentException("End date cannot be before start date");
        return ResponseEntity.ok(financialReportingService.generateIncomeStatement(startDate, endDate));
    }
}
```

### 3. `GLAccountController` (path: `internal/controller/GLAccountController.java`)

- Why exemplary: Illustrates consistent endpoint design for Chart-of-Accounts management, uses `@EmitEvent` and role-based guards, and separates legacy stubs from implemented APIs.
- Pattern: Resource-oriented controller with lifecycle actions (activate/deactivate/archive) and clear authorization scopes.
- Snippet:
  
```java
@RestController
@RequestMapping("/v1/accounting/gl-accounts")
public class GLAccountController {
    @GetMapping
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_LIST", apiVersion = "1")
    public ResponseEntity<Void> listGLAccounts(...) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
```

---

## Business Logic Layer (Services)

### 1. `FinancialReportingService` (path: `service/FinancialReportingService.java`)

- Why exemplary: Well-documented service interface describing reproducible financial report contracts. Methods are parameter-focused and return DTOs suitable for API responses.
- Pattern: Service interface defines behaviors; implementation encapsulates aggregation logic and uses repositories for data access.
- Snippet (interface):

```java
@NonNull
IncomeStatementReport generateIncomeStatement(@NonNull LocalDate startDate, @NonNull LocalDate endDate);

@NonNull
BalanceSheetReport generateBalanceSheet(@NonNull LocalDate asOfDate);

@NonNull
List<AccountDrilldownResponse> drilldownToAccounts(@NonNull String statementLineCode, @NonNull LocalDate startDate, @NonNull LocalDate endDate);
```

### 2. `JournalEntryService` (domain service)

- Why exemplary: Encapsulates lifecycle operations (create, post, reverse) and is used by controllers for domain actions rather than exposing repositories directly.
-Snippet:

```java
public interface JournalEntryService {
    JournalEntry createJournalEntry(JournalEntry entry);
    JournalEntry updateJournalEntry(UUID id, JournalEntry updates);
    JournalEntry postJournalEntry(UUID id);
    JournalEntry reverseJournalEntry(UUID id, String reason);
    Page<JournalEntry> listJournalEntries(Pageable pageable);
}
```

---

## Data Access Layer (Repositories)

### 1. `JournalEntryRepository` (path: `internal/repository/JournalEntryRepository.java`)

- Why exemplary: Uses Spring Data JPA with custom JPQL queries for reporting; includes both paginated and specialized aggregation queries used by services.
- Pattern: Keep complex queries in repository layer; return domain entities for service-level aggregation.
- Snippet (aggregation query):

```java
@Query("""
    SELECT COALESCE(
        SUM(CASE WHEN jel.debitAmount IS NOT NULL THEN jel.debitAmount ELSE 0 END) -
        SUM(CASE WHEN jel.creditAmount IS NOT NULL THEN jel.creditAmount ELSE 0 END),
        0
    )
    FROM JournalEntry je
    JOIN je.lines jel
    WHERE je.status = 'POSTED'
      AND jel.glAccountId = :glAccountId
      AND je.transactionDate >= :startDate
      AND je.transactionDate <= :endDate
""")
java.math.BigDecimal sumPostedBalanceForAccount(UUID glAccountId, LocalDateTime startDate, LocalDateTime endDate);
```

### 2. `StatementLineMappingRepository`

- Why exemplary: Provides access to configurable COA→statement line mappings used by reporting logic.
- Snippet:

```java
public interface StatementLineMappingRepository extends JpaRepository<StatementLineMapping, UUID> {
    List<StatementLineMapping> findByStatementTypeOrderByDisplayOrder(StatementType type);
    List<StatementLineMapping> findByStatementLineCode(String code);
}
```

---

## Domain Models (Entities)

### 1. `StatementLineMapping` (path: `internal/entity/StatementLineMapping.java`)

- Why exemplary: Clean JPA entity with indexes, enumerated types, `@PrePersist` id generation, and denormalized fields for reporting performance.
- Pattern: Entities include only persistence concerns; DTOs are used for API surfaces.
- Snippet:

```java
@Entity
@Table(name = "statement_line_mappings", indexes = {...})
public class StatementLineMapping {
    @Id
    @Column(name = "mapping_id", columnDefinition = "UUID")
    private UUID mappingId;

    @Column(name = "gl_account_id", nullable = false)
    private UUID glAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statement_type", length = 50, nullable = false)
    private StatementType statementType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", length = 50, nullable = false)
    private OperationType operation;

    @PrePersist
    protected void onCreate() { if (mappingId == null) mappingId = UUIDv7Generator.generate(); }
}```

### 2. `JournalEntry` (path: `internal/entity/JournalEntry.java`)

- Why exemplary: Represents a domain aggregate with `lines` as a collection, status enum, and audit timestamps; used across services and repositories.
- Pattern: Aggregate root with child value entities (`JournalEntryLine`) kept in `@OneToMany` collection and mapped for efficient fetches used in reporting queries.
- Snippet:

```java
@Entity
public class JournalEntry {
    @Id private UUID journalEntryId;
    @Enumerated(EnumType.STRING) private JournalEntryStatus status;
    private LocalDateTime transactionDate;
    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL)
    private List<JournalEntryLine> lines;
}
```

## Tests (Integration / Contract)

### 1. `FinancialReportingContractBehaviorIT` (path: `src/test/java/com/positivity/accounting/FinancialReportingContractBehaviorIT.java`)

- Why exemplary: Contract-style integration tests that validate API behavior (happy path, authorization, validation, reproducibility). Uses fixed UUIDs and seeded mappings for deterministic results.
- Pattern: Use `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` for realistic contract tests.
- Snippet:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FinancialReportingContractBehaviorIT {
    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testGenerateIncomeStatement_Success() throws Exception {
        mockMvc.perform(get("/api/v1/reports/financial/income-statement")
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31"))
                .andExpect(status().isOk());
    }
}
```

### 2. `ContractBehaviorIT` (path: `src/test/java/com/positivity/accounting/ContractBehaviorIT.java`)

- Why exemplary: Broad contract tests used across the accounting module to validate common behaviors (CRUD, auth headers, idempotency patterns). Demonstrates header-based gateway simulation and consistent test scaffolding.
- Snippet:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ContractBehaviorIT {
    @Autowired private MockMvc mockMvc;
    @Test void example() throws Exception {
        mockMvc.perform(get("/v1/accounting/journal-entries").header("X-User","testuser"))
               .andExpect(status().isOk());
    }
}
```

---

## Recommendations

- Follow the thin-controller / service-layer pattern seen in `JournalEntryController` and `FinancialReportingService`.
- Keep complex aggregation SQL/JPQL in repositories (as in `JournalEntryRepository`) and perform business rules in services.
- Ensure deterministic tests by seeding required reference data (see `FinancialReportingContractBehaviorIT`).
- Use `@EmitEvent` consistently for important operations to improve observability and audit trails.

---

## Conclusion

This document highlights representative exemplars across layers in `pos-accounting`. Use these examples as templates when adding new features: prefer thin controllers, well-documented service interfaces, repository-level queries for aggregation, and contract-style integration tests for API guarantees.
