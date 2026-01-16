# AGENT_GUIDE.md — Billing Domain

---

## Purpose

The Billing domain manages the full lifecycle of invoices, billing rules, payments, and related financial documents within the POS system. It is the authoritative source for invoice creation, validation, state transitions, tax calculations, and traceability. Billing ensures accurate, auditable, and compliant financial records that integrate with upstream work execution and downstream accounting services.

---

## Domain Boundaries

- **Owned by Billing:**
  - Invoice lifecycle management (Draft, Issued, Paid, Void)
  - Invoice creation from completed Work Orders
  - Tax calculation and financial totals
  - Billing Rules configuration per customer account
  - Payment orchestration and allocation
  - Receipt generation, delivery, and reprint management
  - Enforcement of billing policies during checkout (in collaboration with CRM)

- **External Dependencies:**
  - **Work Execution domain:** Source of truth for Work Order states and BillableScopeSnapshot DTOs
  - **CRM domain:** Customer account data, billing contact info, and billing rules caching
  - **Accounting domain:** Consumes billing events for AR and GL posting
  - **Payment Gateway:** Executes payment authorization, capture, void, and refund
  - **Receipt Service:** Generates and stores receipt content and delivery status

- **Integration Boundaries:**
  - Billing consumes Work Execution APIs/events for billable snapshots and work order readiness
  - Billing publishes invoice and payment events for Accounting consumption
  - Billing manages billing rules and exposes APIs for CRM and Work Execution to consume snapshots
  - Receipt generation and delivery are coordinated with Payment and POS services

---

## Key Entities / Concepts

- **Invoice:** Financial document representing charges for completed work orders; lifecycle states include Draft, Issued, Paid, and Void.
- **InvoiceItem:** Line items on an invoice derived from immutable BillableScopeSnapshot line items.
- **BillableScopeSnapshot:** Immutable snapshot of billable work from Work Execution, including parts, labor, fees, and tax-relevant data.
- **BillingRules:** Customer-specific billing configuration including PO requirements, payment terms, invoice delivery, and grouping strategies.
- **PaymentIntent / PaymentRecord:** Represents payment authorization, capture, void, and refund states and metadata.
- **Receipt:** Customer-facing proof of payment, including printed and emailed versions, with template versioning and audit trail.
- **Traceability Links:** Immutable references on invoices to originating work orders, estimates, approvals, and billing snapshots for auditability.

---

## Invariants / Business Rules

- **Invoice Creation:**
  - Only one primary invoice per Work Order.
  - Invoice generation allowed only if Work Execution reports `invoiceReady=true`.
  - BillableScopeSnapshot is immutable and authoritative for invoice line items.
  - Customer billing data (address, contact method) must be complete before invoice creation.
  - Taxes are calculated by Billing using current tax rules, not sourced from snapshots.
  - Traceability links (`workOrderId`, `billableScopeSnapshotId`, `customerAccountId`) are mandatory and immutable.
  - Idempotency enforced: existing Draft invoices returned; Posted/Paid invoices block regeneration; Voided invoices allow controlled regeneration via privileged endpoint.

- **Invoice Finalization:**
  - Transition from Draft to Issued only if validations pass (customer data, totals, taxes).
  - Issued invoices are immutable; corrections require credit notes.
  - Finalization emits `InvoiceIssued` event for Accounting.

- **Billing Rules:**
  - Billing domain owns BillingRules persistence and validation.
  - Rules are versioned and audited.
  - Work Execution enforces PO requirements using snapshots of BillingRules.
  - PO requirements are strictly enforced during checkout; overrides require permissions and audit trail.
  - Payment terms and invoice delivery methods are configured per account.

- **Payments:**
  - Billing orchestrates payment execution with idempotency and state transitions.
  - Payment gateway interactions are abstracted via adapters.
  - Payment allocations across bills follow deterministic or explicit instructions.
  - GL posting is asynchronous and owned by Accounting.

- **Receipts:**
  - Generated on successful payment capture.
  - Stored with immutable template version and content.
  - Delivered via print or email with retry and fallback policies.
  - Reprints require authorization and are watermarked.
  - Retained for 7 years with tiered storage.

- **Checkout Enforcement:**
  - PO requirements enforced per BillingRules during order finalization.
  - PO format and uniqueness validated.
  - Overrides require permissions, approval workflows, and audit logging.
  - Credit limits enforced to prevent order finalization beyond allowed thresholds.

---

## Events / Integrations

- **Inbound:**
  - Work Execution: BillableScopeSnapshot, Work Order state and readiness flags.
  - CRM: Customer billing data, account lifecycle events (e.g., AccountCreated).
  - Payment Gateway: Payment authorization/capture/void/refund responses.
  - Accounting: Acknowledgements of journal postings.

- **Outbound:**
  - `invoice.draft.created` — emitted on draft invoice creation.
  - `InvoiceIssued` — emitted on invoice finalization.
  - `Billing.PaymentSucceeded.v1` — payment success event for Accounting.
  - Receipt events: `ReceiptGenerated`, `ReceiptPrinted`, `ReceiptEmailed`, `ReceiptReprinted`.
  - Audit events for billing rules changes, PO overrides, payment reversals.

---

## API Expectations (High-Level)

- **Invoice APIs:**
  - Create invoice draft from completed Work Order (idempotent).
  - Retrieve invoice details including traceability links.
  - Issue/finalize invoice with validations.
  - Regenerate invoice from voided state via privileged endpoint (TBD).

- **Billing Rules APIs:**
  - Upsert and retrieve billing rules per account.
  - Event-driven provisioning on account creation.

- **Payment APIs:**
  - Initiate payment authorization and capture with idempotency.
  - Void authorization or refund captured payments with reason codes.
  - Query payment status and history.

- **Receipt APIs:**
  - Generate receipt on payment capture event.
  - Retrieve receipt content and delivery status.
  - Support receipt reprint with authorization and watermarking.

- **Checkout Enforcement APIs:**
  - Validate PO requirement and capture PO reference during order finalization.
  - Support override workflows with multi-approver authentication.

- **Note:** Detailed API endpoints and contracts are TBD.

---

## Security / Authorization Assumptions

- All user actions require authentication and authorization.
- Permissions scoped per role and action, e.g.:
  - `invoice:create`, `invoice:issue`, `invoice:regenerate`
  - `billingRules:manage`
  - `payment:process`, `payment:void`, `payment:refund`
  - `receipt:generate`, `receipt:reprint`
  - `override:poRequirement`
- Sensitive operations (e.g., invoice regeneration, PO overrides, refunds) require elevated permissions and audit logging.
- Customer data access is restricted by tenant/account boundaries.
- PCI-DSS compliance enforced for payment data; no PAN or CVV stored.
- Email addresses encrypted at rest; logs avoid storing sensitive data.

---

## Observability (Logs / Metrics / Tracing)

- **Logging:**
  - Structured logs with correlation IDs for all key operations.
  - Log request receipt, precondition checks, downstream calls, state transitions, errors.
  - Audit logs for billing rules changes, PO overrides, payment reversals, receipt reprints.

- **Metrics:**
  - Invoice creation success/failure counts and latency.
  - Invoice issuance success/failure counts.
  - Payment execution success/failure and retry counts.
  - Receipt generation, print/email delivery success/failure rates.
  - PO override attempts and denials.
  - Reprint counts and authorization failures.

- **Tracing:**
  - Distributed tracing across service calls for invoice creation, payment processing, and receipt generation.
  - Traceability of events from Work Execution through Billing to Accounting.

---

## Testing Guidance

- **Unit Tests:**
  - Validate business rules and invariants for invoice creation, issuance, and payment workflows.
  - BillingRules validation and versioning logic.
  - PO format and uniqueness enforcement.
  - Payment state transitions and idempotency.

- **Integration Tests:**
  - End-to-end invoice draft creation from Work Order with mocked Work Execution responses.
  - Invoice issuance and event emission.
  - Payment execution flows with gateway adapter mocks.
  - Receipt generation and delivery simulation.
  - PO override workflows with permission checks.

- **Contract Tests:**
  - Verify Billing’s consumption of Work Execution BillableScopeSnapshot contract.
  - Verify event payloads for Accounting and Payment domains.

- **Security Tests:**
  - Authorization enforcement on all APIs.
  - Sensitive data masking and encryption verification.

- **Performance Tests:**
  - Invoice creation latency under load.
  - Payment processing throughput.
  - Receipt generation and email delivery SLA adherence.

---

## Common Pitfalls

- **Ignoring Idempotency:** Duplicate invoice creation or payment execution can cause financial discrepancies; always enforce idempotency keys and status checks.

- **Mutable Traceability Links:** Traceability references must be immutable once persisted to ensure auditability.

- **Tax Calculation Delegation:** Do not rely on BillableScopeSnapshot for tax totals; always calculate taxes within Billing using current rules.

- **Incomplete Customer Data:** Invoice creation must fail if billing address or contact method is missing; do not create unusable drafts.

- **Direct DB Coupling to Work Execution:** Billing must consume Work Execution data only via stable API contracts or event projections, never direct DB reads.

- **Insufficient Permission Checks:** Sensitive operations like invoice regeneration, PO overrides, refunds, and receipt reprints require strict authorization and audit trails.

- **Receipt Template Versioning:** Always store and use the original receipt template version for reprints to ensure exact reproduction.

- **Overriding PO Requirements Without Audit:** All overrides must be logged with approver identities and reasons; failure to do so risks compliance violations.

- **Ignoring Payment Gateway Idempotency:** Retry logic must query gateway status to avoid duplicate charges.

- **Not Handling Downstream Failures Gracefully:** Billing operations must rollback or fail cleanly if dependent services (Work Execution, Payment Gateway, Accounting) are unavailable.

---

*End of AGENT_GUIDE.md*
