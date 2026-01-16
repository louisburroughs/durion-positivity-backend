# AGENT_GUIDE.md — Accounting Domain

---

## Purpose
The Accounting domain is responsible for authoritative financial calculations, invoice adjustments, issuance finalization, event ingestion, chart of accounts management, posting category mappings, posting rule configurations, journal entry creation, and ledger posting. It ensures financial data integrity, auditability, and compliance across the POS system.

---

## Domain Boundaries
- **Owned Entities:** Invoice financial state and calculations, CalculationSnapshots, Variances, Invoice Adjustments, Credit Memos, Chart of Accounts (CoA), Posting Categories, Posting Rule Sets, Journal Entries, Ledger Entries.
- **Authoritative Data Ownership:** 
  - Tax and fee rules sourced exclusively from the Tax Configuration Service.
  - Invoice financial totals and adjustments owned by Accounting.
  - Invoice lifecycle state transitions owned by Billing domain.
  - Chart of Accounts and Posting Categories managed within Accounting.
  - Posting Rule Sets and Journal Entries owned and maintained by Accounting.
- **Integration Points:**
  - Tax Configuration Service (authoritative tax/fee rules).
  - Billing domain (Invoice lifecycle and issuance).
  - Work Execution domain (invoice inputs).
  - Event Bus / Message Broker (accounting event ingestion).
  - General Ledger system (posting and balances).
  - External schema repository for canonical event contracts.

---

## Key Entities / Concepts

| Entity                  | Description                                                                                      |
|-------------------------|------------------------------------------------------------------------------------------------|
| **Invoice**             | Represents billing document with financial totals, status, and audit snapshots.                 |
| **InvoiceItem**         | Line items on an invoice, including pricing and taxability attributes.                          |
| **CalculationSnapshot** | Immutable record of tax/fee calculation details for audit and traceability.                     |
| **Variance**            | Records differences between invoice totals and estimate snapshots, with reason codes.          |
| **InvoiceAuditEvent**   | Immutable audit records for invoice adjustments capturing before/after states and reasons.     |
| **CreditMemo**          | Separate document for credit adjustments when invoice totals would become negative.             |
| **GLAccount**           | Chart of Accounts entry with effective dating and classification (Asset, Liability, etc.).      |
| **PostingCategory**     | Business abstraction for financial transaction types, mapped to GL accounts.                    |
| **GLMapping**           | Effective-dated mapping from PostingCategory to GLAccount and financial dimensions.             |
| **PostingRuleSet**      | Versioned rules mapping EventTypes to balanced journal entry lines with conditional logic.      |
| **JournalEntry**        | Draft or posted financial record with balanced debit/credit lines linked to source events.     |
| **LedgerEntry**         | Immutable posted ledger lines updating GL account balances.                                    |
| **AccountingPeriod**    | Defines open/closed periods controlling posting eligibility.                                   |

---

## Invariants / Business Rules

- **Invoice Calculations:**
  - Use only the Tax Configuration Service as the authoritative source for tax and fee rules.
  - Calculations must be immutable once invoice is issued.
  - Monetary rounding uses HALF_UP with currency-scale precision; round per line, then sum.
  - Invoice cannot be issued if tax basis data is incomplete or calculation failed.
  - Variances must be recorded with canonical reason codes; large variances require approval.
  - Adjustments allowed only on Draft invoices; negative totals disallowed; credit memos required for over-credit.
  - Audit trail mandatory for all financial state changes.

- **Chart of Accounts:**
  - Account codes must be unique.
  - Account types limited to ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE.
  - Effective dating governs account activity.
  - Deactivation blocked if account has non-zero balance or violates policy.

- **Posting Categories and Mappings:**
  - Mapping keys uniquely resolve to posting categories.
  - GL mappings are effective-dated and non-overlapping.
  - Deactivated categories cannot be used for new mappings.

- **Posting Rule Sets:**
  - Rules must produce balanced journal entries (debits = credits).
  - Versions are immutable once published and used.
  - Conditional logic based on event payload attributes only.

- **Journal Entries and Posting:**
  - Each source event results in exactly one balanced journal entry or none.
  - Posted journal entries are immutable.
  - Posting allowed only in open accounting periods.
  - Posting is atomic; failures roll back all changes.

- **Event Ingestion:**
  - Events must conform to canonical accounting event schema.
  - Idempotency enforced by eventId; conflicting duplicates flagged and quarantined.
  - Validation includes schema, referential integrity, and financial consistency.
  - Unknown event types handled per policy (reject or suspense).

---

## Key Workflows

### Invoice Totals Calculation
- Triggered when invoice is created or line items change.
- Fetch tax/fee rules from Tax Configuration Service.
- Calculate line taxes, fees, subtotal, total tax, total fees, rounding adjustment, grand total.
- Compare with estimate snapshot; create variance if needed.
- Persist calculation snapshot and update invoice status.

### Authorized Invoice Adjustments
- Allowed only on Draft invoices by authorized users.
- Adjust line items or apply discounts with reason codes and justification.
- Recalculate totals; reject if total < $0.00.
- Persist audit event and mark invoice as adjusted.
- Emit `InvoiceAdjusted` or `CreditMemoIssued` events.

### Invoice Issuance
- Performed by Billing domain on Draft invoices.
- Validate completeness and consistency.
- Assign invoice number, set issued timestamp and user.
- Transition invoice to Issued state (immutable).
- Emit `InvoiceIssued` event for downstream accounting.

### Event Ingestion
- Accept synchronous API and asynchronous broker events.
- Validate envelope, schema, references, and financial consistency.
- Enforce idempotency by eventId.
- Persist raw event and processing status.
- Reject or route unknown/invalid events per policy.

### Chart of Accounts Management
- Create, update (name/description), retrieve, and deactivate GL accounts.
- Enforce uniqueness and effective dating.
- Audit all changes.

### Posting Category and GL Mapping Management
- CRUD operations on posting categories and mappings.
- Enforce uniqueness, effective dating, and no overlaps.
- Resolve mapping keys to GL accounts for transaction dates.

### Posting Rule Set Management
- Create and version posting rule sets mapping EventTypes to GL postings.
- Validate balanced entries and conditional logic.
- Immutable once published.

### Journal Entry Creation and Posting
- Consume domain events and apply posting rules.
- Generate balanced draft journal entries with traceability.
- Post journal entries atomically within open accounting periods.
- Reject unbalanced or invalid entries.

---

## Events / Integrations

| Event Name          | Source Domain | Description                                      | Consumer Domain(s)           |
|---------------------|---------------|------------------------------------------------|-----------------------------|
| `InvoiceAdjusted`   | Accounting    | Emitted on authorized invoice adjustment.       | Accounting, downstream systems |
| `CreditMemoIssued`  | Accounting    | Emitted when a credit memo is created/issued.   | Accounting, downstream systems |
| `InvoiceIssued`     | Billing       | Emitted when invoice is finalized and issued.   | Accounting, AR systems       |
| CanonicalAccountingEvent | Various    | Standardized financial event envelope.           | Accounting ingestion service |
| Audit Events        | Accounting    | Immutable logs for changes and state transitions.| Auditors, Compliance         |

---

## API Expectations (High-Level)

- **Invoice Calculation API:** TBD
- **Invoice Adjustment API:** TBD
- **Invoice Issuance API:** Owned by Billing domain; Accounting consumes events.
- **Event Ingestion API:** Synchronous REST endpoint + asynchronous broker consumer.
- **Chart of Accounts API:** CRUD endpoints with filtering and effective dating.
- **Posting Category & GL Mapping API:** CRUD with validation and date-range enforcement.
- **Posting Rule Set API:** Versioned CRUD with validation.
- **Journal Entry API:** Internal; posting requests with atomic commit.

---

## Security / Authorization Assumptions

- Fine-grained permissions control:
  - `invoice.adjust` for invoice adjustments.
  - `invoice:issue` for invoice issuance (Billing domain).
  - `CoA:Manage` for Chart of Accounts management.
  - `accounting:invoice:approve-variance` for variance approvals.
  - `SCOPE_accounting:events:ingest` for event ingestion.
- Service-to-service authentication enforces sourceModule identity.
- Overrides and exceptions require explicit permission and audit logging.
- Immutable audit trails ensure non-repudiation.

---

## Observability (Logs / Metrics / Tracing)

- **Audit Logs:**
  - Immutable logs for all state transitions, adjustments, postings, and configuration changes.
- **Application Logs:**
  - INFO logs for successful operations (invoice calculations, adjustments, postings).
  - WARN logs for business rule violations or recoverable errors.
  - ERROR logs for failures, conflicts, and validation errors.
- **Metrics:**
  - Invoice calculation success/failure counts and latencies.
  - Invoice variance detection counts by reason code.
  - Invoice adjustment counts and authorization failures.
  - Invoice issuance counts and failures.
  - Event ingestion counts (accepted, replayed, conflicts, rejected).
  - GL journal entry creation and posting success/failure.
  - Posting rule validation errors.
- **Tracing:**
  - Correlation IDs propagated across service boundaries.
  - Traceability from events to journal entries and ledger postings.

---

## Testing Guidance

- **Unit Tests:**
  - Validate calculation logic with varied tax and fee scenarios.
  - Enforce business rules and invariants.
  - Test authorization and permission checks.
- **Integration Tests:**
  - End-to-end invoice lifecycle including adjustments and issuance.
  - Event ingestion with idempotency and conflict scenarios.
  - Posting rule application and journal entry creation.
  - Chart of Accounts and mapping key resolution.
- **Contract Tests:**
  - Validate canonical event schema adherence.
  - Verify event payloads conform to published schemas.
- **Performance Tests:**
  - Measure invoice calculation latency under load.
  - Stress test event ingestion and posting throughput.
- **Security Tests:**
  - Verify permission enforcement on all APIs.
  - Test override and audit trail integrity.
- **Audit & Compliance Tests:**
  - Confirm immutable audit logs for all critical operations.
  - Validate traceability from source events to ledger postings.

---

## Common Pitfalls

- **Tax Basis Data Missing:** Failing to validate mandatory tax basis fields leads to calculation failures and blocked issuance.
- **Rounding Errors:** Incorrect rounding order or mode can cause audit discrepancies; always round per line with HALF_UP.
- **Unauthorized Adjustments:** Allowing adjustments without proper permissions undermines auditability and compliance.
- **Immutability Violations:** Modifying issued invoices or posted journal entries breaks financial integrity.
- **Overlapping GL Mappings:** Overlapping effective dates cause ambiguous posting resolutions.
- **Unbalanced Journal Entries:** Posting unbalanced entries leads to ledger corruption.
- **Event Idempotency Conflicts:** Not handling conflicting duplicates causes data integrity issues.
- **Ignoring Accounting Period Status:** Posting to closed periods violates accounting controls.
- **Insufficient Audit Logging:** Missing audit trails impede compliance and forensic investigations.
- **Improper Error Handling:** Failing to handle service unavailability or validation errors gracefully leads to system instability.

---

# End of AGENT_GUIDE.md
