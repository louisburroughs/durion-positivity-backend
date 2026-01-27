---
title: Accounting Service - Contract Behavioral Testing
description: Integration tests validating behavioral contracts per BACKEND_CONTRACT_GUIDE.md
version: 1.0
---

## Contract Behavioral Testing for Accounting Service

This document describes the behavioral contract testing approach for the accounting service, implemented via `ContractBehaviorIT.java`.

### Overview

The `ContractBehaviorIT` test suite validates that the accounting service adheres to its behavioral contracts defined in `durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md`.

**Key Characteristics**:
- Tests run against **actual service in test mode** (using `@SpringBootTest` with random port)
- NOT mock-only tests; real controller + Spring context
- Organized by contract scenario category (happy path, validation errors, idempotency, concurrency)
- Each test maps to an "Example" scenario in the contract guide
- Validates JSON field naming, data types, monetary precision, timestamps, error responses
- Enforces optimistic locking and balance invariants

### Contract Scope

#### Happy Path Scenarios (CP-xxx)

| Test | Contract Validation | Endpoint | Expected |
|------|-------------------|----------|----------|
| CP-001 | Create GL Account with valid fields | POST `/api/v1/accounting/gl-accounts` | 201 Created, glAccountId, versionNumber |
| CP-002 | Create balanced Journal Entry | POST `/api/v1/accounting/journal-entries` | 201 Created, totalDebit == totalCredit |

#### Validation Error Scenarios (VE-xxx)

| Test | Contract Validation | Endpoint | Expected |
|------|-------------------|----------|----------|
| VE-001 | Reject invalid account code format | POST `/api/v1/accounting/gl-accounts` | 400 Bad Request, fieldErrors array |
| VE-002 | Reject unbalanced journal entry | POST `/api/v1/accounting/journal-entries` | 400 Bad Request, errorCode: JOURNAL_ENTRY_UNBALANCED |
| VE-003 | Reject duplicate GL Account code | POST `/api/v1/accounting/gl-accounts` | 409 Conflict, errorCode: DUPLICATE_ACCOUNT_CODE |

#### Idempotency Scenarios (ID-xxx)

| Test | Contract Validation | Mechanism | Expected |
|------|-------------------|-----------|----------|
| ID-001 | Same Idempotency-Key returns same resource | Header: `Idempotency-Key` | Same journalEntryId on second POST |

#### Concurrency-Safe Invariants (CC-xxx)

| Test | Contract Validation | Mechanism | Expected |
|------|-------------------|-----------|----------|
| CC-001 | Optimistic locking prevents concurrent updates | versionNumber field | 409 Conflict if version stale |
| CC-002 | Journal Entry balance invariant maintained | Debit/Credit validation | totalDebit always == totalCredit |

#### Field Format Validation (FF-xxx)

| Test | Contract Validation | Field | Expected |
|------|-------------------|-------|----------|
| FF-001 | Monetary precision (2 decimal places) | debitAmount, creditAmount, totalDebit, totalCredit | Exact 2 decimal precision |
| FF-002 | Timestamps in ISO 8601 format | createdAt, updatedAt | Pattern: YYYY-MM-DDTHH:MM:SS[.sss][±HH:MM] |

### Running the Tests

#### Run All Contract Tests
```bash
cd durion-positivity-backend/pos-accounting
./mvnw test -Dtest=ContractBehaviorIT
```

#### Run Specific Test Category
```bash
# Happy path only
./mvnw test -Dtest=ContractBehaviorIT -Dgroups="happy-path"

# Validation errors only
./mvnw test -Dtest=ContractBehaviorIT -Dgroups="validation"

# Concurrency tests
./mvnw test -Dtest=ContractBehaviorIT -Dgroups="concurrency"
```

#### Run Specific Test
```bash
./mvnw test -Dtest=ContractBehaviorIT#testCreateGLAccountHappyPath
```

### Contract Compliance Checklist

The tests validate compliance with:

- ✅ **JSON Field Naming**: All fields are camelCase (per contract mandate)
- ✅ **Data Types**: Numeric fields use Integer/Long, IDs use String, amounts use BigDecimal
- ✅ **Monetary Precision**: All amounts are stored/returned with exactly 2 decimal places
- ✅ **Timestamps**: All timestamps in ISO 8601 format with timezone
- ✅ **Error Response Format**: Includes errorCode, errorMessage, errorDetails, timestamp
- ✅ **Optimistic Locking**: versionNumber included in responses for all mutable resources
- ✅ **Effective Dating**: GL Accounts support effectiveFrom/effectiveTo for temporal validity
- ✅ **Balance Invariant**: Journal entries always have totalDebit == totalCredit
- ✅ **Idempotency**: POST requests with Idempotency-Key header return same resource

### Contract Guide Reference

Full contract specifications: `durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md`

Key sections referenced by tests:
- Section 2: JSON Field Naming Conventions (camelCase requirement)
- Section 3: Data Types (Integer, Long, String, BigDecimal, Timestamp)
- Section 4: GL Account Entity (account code format, effective dating)
- Section 5: Journal Entry Entity (balance invariant, line requirements)
- Section 6: Error Response Format (errorCode, fieldErrors array)
- Section 7: Optimistic Locking (versionNumber, 409 Conflict)
- Section 8: Audit Trail Requirements (createdAt, createdBy, updatedAt, updatedBy)

### Integration with contract-sync.yml Workflow

This test suite is designed to work with the `contract-sync.yml` workflow:

1. **PR Trigger**: When a PR is created touching accounting controllers/DTOs/schemas
2. **Contract Check**: Workflow verifies PR references contract PR in durion
3. **Test Execution**: Runs `ContractBehaviorIT` to validate contract compliance
4. **Report**: Adds comment with test results and contract coverage gaps
5. **Block Merge**: Blocks PR merge if contract tests fail

### Adding New Contract Tests

When updating `BACKEND_CONTRACT_GUIDE.md` with new Examples:

1. Add new test method to `ContractBehaviorIT.java`
2. Follow naming pattern: `test{ScenarioName}{Category}()`
3. Include scenario code comment (e.g., `// CP-001: ...`)
4. Map test assertions to contract Example requirements
5. Run locally: `./mvnw test -Dtest=ContractBehaviorIT#testNewMethod`
6. Commit with reference to contract PR: `[CAP-###] Add contract test for {scenario}`

### Troubleshooting

#### Test Fails with 404
- **Cause**: Endpoint not implemented or incorrect path
- **Fix**: Verify endpoint exists in respective controller and matches `apiV1` base path
- **Reference**: Check actual controller code against contract guide endpoint list

#### Test Fails with 400 Bad Request
- **Cause**: Request payload doesn't match contract DTOs
- **Fix**: Verify DTO fields match contract camelCase naming; add missing required fields
- **Reference**: Check BACKEND_CONTRACT_GUIDE.md for required vs optional fields

#### Monetary Precision Assertion Fails
- **Cause**: BigDecimal rounding or JSON serialization issue
- **Fix**: Ensure `@JsonProperty(value = "debitAmount")` exists on DTO fields and ObjectMapper uses correct serialization
- **Reference**: BigDecimal should use `HALF_UP` rounding with scale 2

#### Timestamp Format Mismatch
- **Cause**: Timezone not included or different ISO 8601 variant
- **Fix**: Use `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")` on ZonedDateTime fields
- **Reference**: Verify LocalDateTime is converted to ZonedDateTime in response DTOs

### CI/CD Integration

These tests are automatically run as part of:
- **Local Development**: `./mvnw clean test`
- **GitHub Actions**: `.github/workflows/ci.yml` → integration-test job
- **Pull Request Validation**: `contract-sync.yml` workflow on controller/DTO changes

### Performance Notes

- **Duration**: ~15-30 seconds for full suite (depending on test data setup)
- **Concurrency**: Tests run in parallel; use `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` if ordering needed
- **Test Data**: Uses in-memory H2 database in test profile for isolation
- **Cleanup**: Spring Test context handles rollback automatically via `@Transactional`

### Contract Versioning

| Version | Date | Changes | Status |
|---------|------|---------|--------|
| 1.0 | 2024-01-26 | Initial contract guide and test suite | draft |

---

**Next Steps**: 
1. Create similar `ContractBehaviorIT` for remaining 22 pos-* services
2. Reference respective service domain `.business-rules/BACKEND_CONTRACT_GUIDE.md`
3. Configure `contract-sync.yml` workflow to validate PRs against contract tests
