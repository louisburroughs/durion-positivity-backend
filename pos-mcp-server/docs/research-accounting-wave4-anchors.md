---
title: Research - Accounting Wave 4 Anchors
module: pos-accounting
issue: 1124
wave: 4
date: 2026-07-29
owner: documentation-agent
---

## Scope

- Goal: source-verified anchors for four docs:
  - accounting.journal-entries
  - accounting.financial-statements
  - accounting.report-export
  - accounting.codes
- Source boundary enforced: only files under /home/n541342/IdeaProjects/durion-positivity-backend/pos-accounting.
- This document records concrete implementation facts, tokens, and mismatch notes for Wave 4.

## Verified source inventory table

| Area | Source file | Anchor highlights |
| --- | --- | --- |
| Journal entries API | src/main/java/com/positivity/accounting/internal/controller/JournalEntryController.java | 51, 84, 92, 169, 176, 210, 253, 267, 315 |
| Journal posting/reversal rules | src/main/java/com/positivity/accounting/internal/service/JournalEntryServiceImpl.java | 72, 77, 282, 284, 331-363, 402, 446, 450, 456, 621 |
| Journal lifecycle model | src/main/java/com/positivity/accounting/internal/entity/JournalEntry.java | 117-127, 129-133, 153-158, 229-236 |
| Reversal error mapping | src/main/java/com/positivity/accounting/internal/exception/JournalEntryNotReversibleException.java | 8-11 |
| Period lock gate | src/main/java/com/positivity/accounting/internal/service/AccountingPeriodGate.java | 72, 103-142, 165-176 |
| Period row lock | src/main/java/com/positivity/accounting/internal/repository/AccountingPeriodRepository.java | 30-31 |
| Error code envelope mapping | src/main/java/com/positivity/accounting/internal/config/AccountingExceptionHandler.java | 82, 115, 126, 136, 384-387 |
| Financial statements API | src/main/java/com/positivity/accounting/internal/controller/FinancialReportingController.java | 51, 68-70, 111-113, 139-141, 180-182, 234-236, 295-297, 354-356, 395-397, 437-439 |
| Financial derivation logic | src/main/java/com/positivity/accounting/internal/service/FinancialReportingServiceImpl.java | 115, 161, 202, 248, 343, 353, 523, 546, 622, 690, 776, 1028-1029 |
| Ledger aggregation queries | src/main/java/com/positivity/accounting/internal/repository/JournalEntryRepository.java | 124-142, 153-166, 181-198, 209-222, 239-252 |
| GL metadata/types | src/main/java/com/positivity/accounting/internal/entity/GLAccount.java | 82-97, 174-186 |
| GL account lookup/query | src/main/java/com/positivity/accounting/internal/repository/GLAccountRepository.java | 20-57 |
| Report export API | src/main/java/com/positivity/accounting/internal/controller/ReportExportController.java | 49, 68-69, 94-95, 126-127, 162-163 |
| Report export flow | src/main/java/com/positivity/accounting/internal/service/ReportExportServiceImpl.java | 69, 82, 91, 139, 156, 163-170, 180-182, 245, 266, 278-286 |
| Report export contract DTOs | src/main/java/com/positivity/accounting/internal/dto/ReportExportRequest.java | 26-29, 37-39, 46-55 |
| Report export status DTO | src/main/java/com/positivity/accounting/internal/dto/ReportExportResponse.java | 26-27, 40-44, 47-53 |
| Permissions catalog | src/main/resources/permissions.yaml | 53, 55, 57, 59, 77, 103, 107 |
| Event registry | src/main/java/com/positivity/accounting/internal/config/EventTypes.java | 61-70, 152-186, 281-288 |
| Relevant enums | src/main/java/com/positivity/accounting/internal/enums/*.java | JournalEntryStatus, JournalEntryType, ManualJEReasonCode, AccountType, AccountSubtype, GLAccountStatus, ExportFormat, ExportStatus |

## journal entries facts

- Controller base path is /v1/accounting/journal-entries (JournalEntryController.java:51).
- Endpoint permissions and emitted events:
  - GET /v1/accounting/journal-entries -> accounting:je:view, ACCOUNTING_JOURNAL_ENTRY_LIST (84, 92).
  - POST /v1/accounting/journal-entries -> accounting:je:create, ACCOUNTING_JOURNAL_ENTRY_CREATE (169, 176).
  - PUT /v1/accounting/journal-entries/{journalEntryId} -> accounting:je:create, ACCOUNTING_JOURNAL_ENTRY_UPDATE (189, 196).
  - POST /v1/accounting/journal-entries/{journalEntryId}/post -> accounting:je:post, ACCOUNTING_JOURNAL_ENTRY_POST (210, 253).
  - POST /v1/accounting/journal-entries/{journalEntryId}/reverse -> accounting:je:reverse, ACCOUNTING_JOURNAL_ENTRY_REVERSE (267, 315).
- Post invariants in JournalEntryServiceImpl:
  - Balance tolerance: BALANCE_TOLERANCE = 0.0001 (72), enforced in validateBalance (621).
  - Status gate: only DRAFT can post; else IllegalStateException maps to ENTRY_ALREADY_POSTED (266-271 in service + 384-387 in exception handler).
  - Period gate is mandatory pre-post: assertPostingAllowed(transactionDate, journalEntryId, overrideJustification) (282).
  - Entry numbering assigned at post time with per-month scope key JE-YYYYMM and unpadded sequence (331-363).
- Reverse invariants in JournalEntryServiceImpl:
  - Only POSTED may reverse; otherwise JournalEntryNotReversibleException (402).
  - Reversal posts immediately as POSTED and gets its own JE-YYYYMM-seq entry number (450).
  - Original status flip is race-safe by conditional repository update markReversed(... status=POSTED ...) (456 and JournalEntryRepository.java:88-106).
  - Period gate re-applied on resolved reversal date (446).
  - Reversal event persisted via outbox with event type JournalEntryReversed constant (77, 532).
- Immutability and lifecycle anchors:
  - Entity documents POSTED immutability and isImmutable() returns true for POSTED or REVERSED (229-236).
  - State machine represented by JournalEntryStatus tokens DRAFT, PENDING, POSTED, REVERSED.
- Lock/error anchors:
  - Period gate reads period row with PESSIMISTIC_WRITE lock (AccountingPeriodRepository.java:30-31) and raises PERIOD_CLOSED / PERIOD_HARD_LOCKED via exception handler mappings (126, 136).
  - Reversal conflict maps to JE_ALREADY_REVERSED or JE_NOT_POSTED (115).

Journal-entry tokens for seed lists:
- JournalEntryStatus: DRAFT, PENDING, POSTED, REVERSED
- JournalEntryType: EVENT_DRIVEN, MANUAL
- ManualJEReasonCode: ACCRUAL_ADJUSTMENT, ERROR_CORRECTION, RECLASSIFICATION, DEPRECIATION, OTHER
- Error codes in this surface: UNBALANCED_ENTRY, ENTRY_ALREADY_POSTED, JE_ALREADY_REVERSED, JE_NOT_POSTED, PERIOD_CLOSED, PERIOD_HARD_LOCKED

## financial statements facts

- Controller base path is /v1/accounting/reports/financial (FinancialReportingController.java:51).
- All financial statement endpoints are permission-gated by reporting:view:financial-statements and emit event IDs:
  - income-statement -> REPORT_INCOME_STATEMENT_GENERATE (68-70)
  - balance-sheet -> REPORT_BALANCE_SHEET_GENERATE (111-113)
  - trial-balance -> REPORT_TRIAL_BALANCE_GENERATE (139-141)
  - drilldown/accounts -> REPORT_DRILLDOWN_ACCOUNTS (180-182)
  - drilldown/journal-lines -> REPORT_DRILLDOWN_JOURNAL_LINES (234-236)
  - general-ledger -> REPORT_GENERAL_LEDGER_GENERATE (295-297)
  - aged-receivables -> REPORT_AGED_RECEIVABLES_GENERATE (354-356)
  - aged-payables -> REPORT_AGED_PAYABLES_GENERATE (395-397)
  - tax-liability -> REPORT_TAX_LIABILITY_GENERATE (437-439)
- Data derivation facts from implementation:
  - Income statement aggregates mapped accounts using posted JE balances via sumPostedBalanceForAccount (FinancialReportingServiceImpl.java:202 + JournalEntryRepository.java:124-142).
  - Balance sheet uses posted balances as-of date via sumPostedBalanceAsOf (FinancialReportingServiceImpl.java:248 + JournalEntryRepository.java:153-166).
  - Trial balance uses DB-side grouped totals from POSTED journal lines via sumPostedDebitsCreditsByAccountAsOf (FinancialReportingServiceImpl.java:353 + JournalEntryRepository.java:181-198).
  - General ledger loads only POSTED entries in range and builds running balances (FinancialReportingServiceImpl.java:546 + JournalEntryRepository.java:239-252).
  - Tax liability reconciliation uses GL account code 2200 as Sales-Tax Payable anchor and compares report net tax to posted GL activity (FinancialReportingServiceImpl.java:115, 1028-1029).
  - Aged receivables and aged payables are computed from accounting-owned replicas and balances (FinancialReportingServiceImpl.java:622, 690).
- Account type/subtype/status tokens implemented:
  - AccountType: ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE.
  - AccountSubtype: RECEIVABLE, PAYABLE, BANK_CASH, UNDEPOSITED_FUNDS, TAX_PAYABLE, CURRENT_ASSET, FIXED_ASSET, CURRENT_LIABILITY, SALES, COST_OF_SALES, OPERATING_EXPENSE, OTHER.
  - GLAccountStatus enum: ACTIVE, INACTIVE, NOT_YET_ACTIVE.
  - GLAccount derived status logic is date-based in entity getDerivedStatus() (GLAccount.java:174-186).

## report export facts

- Controller base path is /v1/accounting/reports/export (ReportExportController.java:49).
- Endpoint permissions and events:
  - POST /v1/accounting/reports/export -> accounting:report:export, ACCOUNTING_REPORT_EXPORT_REQUEST (68-69).
  - GET /v1/accounting/reports/export/{exportId} -> accounting:report:export, ACCOUNTING_REPORT_EXPORT_STATUS (94-95).
  - GET /v1/accounting/reports/export/{exportId}/download -> reporting:view:financial-statements, ACCOUNTING_REPORT_EXPORT_DOWNLOAD (126-127).
  - GET /v1/accounting/reports/export -> accounting:report:export, ACCOUNTING_REPORT_EXPORT_LIST (162-163).
- Service flow in ReportExportServiceImpl:
  - requestExport() immediately calls render() and stores resulting terminal state in exportStore (139, 143, 156).
  - Supported report types are explicit constants set (69).
  - Supported formats in v1 runtime are only CSV/PDF; other enum values are marked failed (169-170).
  - On success, status is COMPLETED and downloadUrl is /v1/accounting/reports/export/{id}/download (180-182).
  - Artifacts stored in bounded in-memory LRU map with MAX_ARTIFACTS=100 (91, 114).
  - downloadExport() returns 404 for missing job/artifact and 409 when status is not COMPLETED (266, 278-286).
  - As-of report types consume endDate as as-of date via AS_OF_REPORT_TYPES (82, 245).
- Export tokens:
  - ExportFormat enum: PDF, CSV, XLSX, JSON.
  - ExportStatus enum: PENDING, IN_PROGRESS, COMPLETED, FAILED.

## codes token catalog seed lists

Seed list A: Wave-4 relevant enum tokens
- JournalEntryStatus: DRAFT, PENDING, POSTED, REVERSED
- JournalEntryType: EVENT_DRIVEN, MANUAL
- ManualJEReasonCode: ACCRUAL_ADJUSTMENT, ERROR_CORRECTION, RECLASSIFICATION, DEPRECIATION, OTHER
- AccountType: ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
- AccountSubtype: RECEIVABLE, PAYABLE, BANK_CASH, UNDEPOSITED_FUNDS, TAX_PAYABLE, CURRENT_ASSET, FIXED_ASSET, CURRENT_LIABILITY, SALES, COST_OF_SALES, OPERATING_EXPENSE, OTHER
- GLAccountStatus: ACTIVE, INACTIVE, NOT_YET_ACTIVE
- ExportFormat: PDF, CSV, XLSX, JSON
- ExportStatus: PENDING, IN_PROGRESS, COMPLETED, FAILED
- StatementType (used by report mapping): INCOME_STATEMENT, BALANCE_SHEET, LABOR_OVERHEAD
- OperationType (statement line calc): SUM, SUBTRACT, NEGATE

Seed list B: Permission codes relevant to Wave 4 docs
- accounting:je:view
- accounting:je:create
- accounting:je:post
- accounting:je:reverse
- accounting:period:override
- accounting:report:export
- reporting:view:financial-statements

Seed list C: Core constants for Wave 4 anchors
- JournalEntryServiceImpl.BALANCE_TOLERANCE = 0.0001
- JournalEntryServiceImpl.EVENT_TYPE_JOURNAL_ENTRY_REVERSED = JournalEntryReversed
- FinancialReportingServiceImpl.BALANCE_TOLERANCE = 0.01
- FinancialReportingServiceImpl.ENTRY_NUMBER_SCOPE_PREFIX = JE-
- FinancialReportingServiceImpl.SALES_TAX_PAYABLE_ACCOUNT_CODE = 2200
- ReportExportServiceImpl.SUPPORTED_REPORT_TYPES = TAX_LIABILITY, INCOME_STATEMENT, BALANCE_SHEET, TRIAL_BALANCE, GENERAL_LEDGER, AGED_RECEIVABLES, AGED_PAYABLES
- ReportExportServiceImpl.AS_OF_REPORT_TYPES = BALANCE_SHEET, TRIAL_BALANCE, AGED_RECEIVABLES, AGED_PAYABLES
- ReportExportServiceImpl.MAX_ARTIFACTS = 100

Seed list D: Event registry IDs for Wave 4 surfaces
- Journal entries: ACCOUNTING_JOURNAL_ENTRY_LIST, ACCOUNTING_JOURNAL_ENTRY_CREATE, ACCOUNTING_JOURNAL_ENTRY_UPDATE, ACCOUNTING_JOURNAL_ENTRY_POST, ACCOUNTING_JOURNAL_ENTRY_REVERSE
- Financial statements: REPORT_INCOME_STATEMENT_GENERATE, REPORT_BALANCE_SHEET_GENERATE, REPORT_TRIAL_BALANCE_GENERATE, REPORT_DRILLDOWN_ACCOUNTS, REPORT_DRILLDOWN_JOURNAL_LINES, REPORT_GENERAL_LEDGER_GENERATE, REPORT_AGED_RECEIVABLES_GENERATE, REPORT_AGED_PAYABLES_GENERATE, REPORT_TAX_LIABILITY_GENERATE
- Report export: ACCOUNTING_REPORT_EXPORT_REQUEST, ACCOUNTING_REPORT_EXPORT_STATUS, ACCOUNTING_REPORT_EXPORT_LIST, ACCOUNTING_REPORT_EXPORT_DOWNLOAD

Module-wide enum inventory (for broader accounting.codes expansion)
- Source set: src/main/java/com/positivity/accounting/internal/enums/*.java
- Extracted families include: AccountingEventStatus, AccountingIntent, AccountingPeriodStatus, AccountingStatus, AccountSubtype, AccountType, AllocationStrategy, APPaymentStatus, BankAdjustmentType, BankReconciliationLineStatus, CancellationType, CostType, CreditMemoStatus, CustomerCreditStatus, ExportFormat, ExportStatus, FeeRepresentation, GLAccountStatus, InvoiceStatus, JournalEntryStatus, JournalEntryType, ManualJEReasonCode, MatchConfidence, MatchedPaymentType, MatchReferenceField, OperationType, PaymentMethod, PaymentOutcomeType, PaymentStatus, PostingFailureReason, PostingRuleSetState, ReconciliationStatus, RefundMethod, RefundPaymentStatus, RefundType, ReprocessingOutcome, SettlementLineMatchStatus, SettlementLineType, SettlementStatus, StatementType, TaxLiabilitySnapshotStatus, VendorBillStatus, VendorStatus.

## declared-but-unused or mismatches

Wave-4 specific mismatches/notes
- Report export behavior mismatch versus async wording:
  - API/docs language says async and initial PENDING expected, but service executes render synchronously in requestExport -> render and often returns COMPLETED/FAILED immediately (ReportExportServiceImpl.java:139, 143, 156, 180).
- ExportFormat mismatch:
  - Enum declares XLSX and JSON, but runtime accepts only CSV/PDF; others force FAILED (ExportFormat.java and ReportExportServiceImpl.java:169-170).
- ExportStatus mismatch:
  - Enum and DTO mention PENDING/IN_PROGRESS, but current service path writes terminal COMPLETED/FAILED directly and no in-progress worker state is implemented (ReportExportServiceImpl.java:180, 188, 195).
- Journal status lifecycle note:
  - JournalEntryStatus includes PENDING, but JournalEntryServiceImpl manual create/post/reverse path is DRAFT -> POSTED -> REVERSED; no direct transition to PENDING in this service path.

Cross-module code-catalog mismatches discovered while validating registries
- Emit event IDs present in controllers but absent in EventTypes registry:
  - ACCOUNTING_DEFAULT_MAPPING_CREATE
  - ACCOUNTING_DEFAULT_MAPPING_DELETE
  - ACCOUNTING_DEFAULT_MAPPING_LIST
  - ACCOUNTING_DEFAULT_MAPPING_UPDATE
  - ACCOUNTING_MAPPING_KEY_CREATE
  - ACCOUNTING_MAPPING_KEY_DEACTIVATE
  - ACCOUNTING_MAPPING_KEY_LIST
  - ACCOUNTING_MAPPING_KEY_UPDATE
  - ACCOUNTING_POSTING_CATEGORY_CREATE
  - ACCOUNTING_POSTING_CATEGORY_DEACTIVATE
  - ACCOUNTING_POSTING_CATEGORY_LIST
  - ACCOUNTING_POSTING_CATEGORY_UPDATE
  - ACCOUNTING_POSTING_RULE_UPDATE
  - AP_PAYMENT_EXECUTE
- EventTypes registry IDs with no @EmitEvent producer found:
  - ACCOUNTING_GL_ACCOUNT_CREATE_LEGACY
  - ACCOUNTING_GL_ACCOUNT_UPDATE_LEGACY
  - ACCOUNTING_STATUS_SYNC_PROCESS
  - ACCOUNTING_STATUS_VIEW
  - AP_PAYMENT_GL_POSTING
  - PAYMENT_APPLICATION_GL_POSTING
  - PAYMENT_APPLICATION_REVERSAL_GL_POSTING
  - VENDOR_BILL_GL_POSTING
- Permission literals used in hasAuthority(...) but not declared in permissions.yaml:
  - VIEW_ACCOUNTING_STATUS
  - VIEW_ACCOUNTING_DETAIL
  - REFRESH_ACCOUNTING_STATUS
- Declared permission with no literal hasAuthority(...) call:
  - accounting:payment:reverse
- Declared permission accounting:period:override is used indirectly via constant OVERRIDE_AUTHORITY and SecurityContextHelper.hasAuthority(OVERRIDE_AUTHORITY), not as a string literal annotation.

## open risks/ambiguities

- accounting.report-export contract risk:
  - Client polling flows that expect PENDING/IN_PROGRESS may see immediate terminal states; generated SDK behavior should treat status transitions as optional.
- accounting.report-export format risk:
  - XLSX/JSON are schema-visible via enum but not executable; downstream consumers may assume support incorrectly.
- accounting.journal-entries lifecycle ambiguity:
  - PENDING status exists in enum and error mapping paths, but Wave-4 manual JE endpoints do not expose a path entering PENDING.
- accounting.financial-statements historical-balance caveat:
  - Aged receivables/payables implementation notes current-balance with as-of date bucketing limitations in comments; if the Wave-4 doc claims strict historical reconstruction, it will overstate current behavior.
- codes governance risk:
  - Event registry and annotation drift (listed above) means some event IDs may silently miss startup registration, depending on enforcement coverage in deployment pipelines.
