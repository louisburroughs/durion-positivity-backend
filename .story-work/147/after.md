## 🏷️ Labels (Proposed)

### Required
- type:story
- domain:billing
- status:ready-for-dev

### Recommended
- agent:story-authoring
- agent:billing
- agent:workexec

### Blocking / Risk
- none

**Rewrite Variant:** integration-conservative

---

## Story Intent
**As a** Billing Administrator (or automated billing process),
**I want** invoice drafts to persist immutable traceability links back to the originating workorder, estimate, and approval artifacts,
**so that** every invoice is auditable and defensible with a clear lineage to authorized scope of work.

## Actors & Stakeholders
- **Billing Service (Primary System Actor):** Creates invoices and snapshots traceability references.
- **Workexec Service (Upstream SoR):** Source of truth for billability state and canonical identifiers.
- **Billing Administrator (User):** Initiates invoice draft generation and reviews traceability.
- **Auditor (Stakeholder):** Requires end-to-end lineage.

## Preconditions
- A `Workorder` exists.
- Workexec is the source of truth for whether a workorder (or work items) are billable.
- Workexec exposes a **stable, versioned read model** (API and/or event projection) for billing to consume (no direct table reads).

### Workexec Read Model (Consumed by Billing)
Billing consumes a workexec-provided “billable work summary” that includes, at minimum:
- `workorderId` (required)
- `billableStatus` (required; e.g., `BILLABLE | NOT_BILLABLE | BILLABLE_PENDING_APPROVAL`)
- `customerId` and/or `accountId` linkage (required; used to prevent cross-customer invoicing)
- `estimateId` or `estimateVersionId` (optional)
- `approvalId` (optional)
- `readyForInvoicingAt` (optional)
- `schemaVersion` (required)

## Functional Behavior
### Trigger
A request is received to generate a **Draft Invoice** for a given `workorderId`.

### Main Success Scenario
1. Billing receives a request to generate a draft invoice for `workorderId`.
2. Billing calls the workexec read model to retrieve billable status and traceability identifiers.
3. Billing validates:
	 - `billableStatus == BILLABLE`
	 - The `customerId/accountId` from workexec matches the invoice customer/account context.
4. Billing creates a new `Invoice` in `DRAFT`.
5. Billing snapshots traceability references onto the invoice as immutable fields (at invoice-level and/or line-level):
	 - `sourceWorkorderId` (required)
	 - `sourceEstimateId` / `sourceEstimateVersionId` (if provided)
	 - `sourceApprovalId(s)` (if provided)
	 - `sourceSchemaVersion` (the workexec contract version used)
6. Billing returns the created `invoiceId`.

## Alternate / Error Flows
- **Flow 1: Workorder Not Billable**
	- If `billableStatus != BILLABLE`, reject invoice draft creation.
	- **Outcome:** return `WORKORDER_NOT_BILLABLE` (or equivalent) and create no invoice.

- **Flow 2: Cross-Customer / Cross-Account Mismatch**
	- If the `customerId/accountId` from workexec does not match the invoice context, reject.
	- **Outcome:** return `WORKORDER_CUSTOMER_MISMATCH` (or equivalent) and create no invoice.

- **Flow 3: Workexec Contract Unavailable**
	- If workexec read model cannot be retrieved (timeout/5xx), reject.
	- **Outcome:** return an error without creating an invoice; log with a distinct reason code for operational visibility.

- **Flow 4: Missing Traceability Identifiers**
	- If workexec returns missing identifiers that are required by configured billing policy (e.g., approval required), invoice issuance must be blocked.
	- **Outcome:** do not allow transition to `ISSUED` until required identifiers are present; alert for administrative review.

## Business Rules
- **BR-1 (Domain Ownership):**
	- Workexec is the **source of truth** for billability state and canonical identifiers.
	- Billing is the **source of truth** for invoices and snapshots traceability at invoice creation.
- **BR-2 (No DB Coupling):** Billing must not query internal workexec tables/models directly.
- **BR-3 (Snapshot Immutability):** Once persisted, invoice traceability references are immutable.
- **BR-4 (Issuance Gating):** Company policy dictates an invoice cannot transition from `DRAFT` to `ISSUED` unless required traceability identifiers are present/valid.
- **BR-5 (Scope):** UI rendering and customer-facing invoice document formatting are out of scope for this backend story (separate follow-up stories).

## Data Requirements
### Entity: `Invoice`
Billing persists traceability references as invoice-level fields and/or per-line fields (implementation choice), including:

| Field Name | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `invoiceId` | UUID | PK | |
| `status` | Enum | includes `DRAFT`, `ISSUED` | |
| `sourceWorkorderId` | UUID/String | Not null, immutable | Identifier from workexec |
| `sourceEstimateId` / `sourceEstimateVersionId` | UUID/String | Nullable, immutable | Snapshot if provided by workexec |
| `sourceApprovalIds` | Array(UUID/String) | Nullable, immutable | Snapshot if provided by workexec |
| `sourceSchemaVersion` | String | Not null, immutable | Workexec contract schema version used |

## Acceptance Criteria
- **AC-1: Draft Invoice Stores Immutable Traceability Snapshot**
	- Given workexec returns `billableStatus=BILLABLE` for a `workorderId`
	- When billing creates a draft invoice for that `workorderId`
	- Then the created invoice persists immutable traceability references copied from the workexec contract (including `sourceWorkorderId` and any provided estimate/approval identifiers)
	- And billing records which workexec contract `schemaVersion` was used (`sourceSchemaVersion`).

- **AC-2: Non-Billable Workorders Cannot Create Draft Invoices**
	- Given workexec returns `billableStatus != BILLABLE`
	- When billing receives a request to create a draft invoice
	- Then billing rejects the request and creates no invoice.

- **AC-3: Issuance is Blocked When Policy-Required Traceability is Missing**
	- Given an invoice is in `DRAFT`
	- And billing policy requires certain traceability identifiers (e.g., approval)
	- When an attempt is made to transition the invoice to `ISSUED`
	- Then the transition is rejected unless all required identifiers are present.

- **AC-4: Billing Does Not Couple to Workexec Storage**
	- Given invoice draft creation runs
	- Then billing retrieves billable/traceability data only via the versioned workexec contract (API/event projection), not by reading workexec persistence models directly.

## Audit & Observability
- **AUD-1:** Log successful draft invoice creation at `INFO` with `invoiceId`, `workorderId`, and captured traceability identifiers.
- **AUD-2:** Log rejected invoice creation attempts at `WARN` with `workorderId` and a reason code (`WORKORDER_NOT_BILLABLE`, `WORKORDER_CUSTOMER_MISMATCH`, `WORKEXEC_UNAVAILABLE`, etc.).
- **MET-1:** Increment `invoice.creation.attempts` for every request.
- **MET-2:** Increment `invoice.creation.failures` on failure, tagged by reason.

## Open Questions
None. Domain ownership and scope decisions are resolved in the issue comment “Decision Document — Issue #147” (2026-01-14).

---

## Original Story (Unmodified – For Traceability)
# Issue #147 — [BACKEND] [STORY] Invoicing: Preserve Traceability Links (Estimate/Approval/Workorder)

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Invoicing: Preserve Traceability Links (Estimate/Approval/Workorder)

**Domain**: user

### Story Description

/kiro
Produce implementation-ready acceptance criteria, validations, and edge cases. Keep Moqui state transitions and audit requirements explicit.

# Functional Requirement

## Classification (confirm labels)
- Type: story
- Layer: functional
- Domain: workexec

## Actor
System

## Trigger
Invoice draft is generated.

## Main Flow
1. System stores references from invoice to workorder.
2. System stores references from invoice to originating estimate version.
3. System stores references from invoice to approval artifacts/records.
4. System exposes traceability in UI for authorized roles.
5. System includes reference identifiers in customer-facing invoice where configured.

## Alternate / Error Flows
- Origin artifacts missing due to data corruption → block issuance and alert admin.

## Business Rules
- Invoices must be traceable to the approved scope and executed work.

## Data Requirements
- Entities: Invoice, Workorder, Estimate, ApprovalRecord, DocumentArtifact
- Fields: workorderId, estimateId, estimateVersion, approvalId, artifactRef, traceabilitySummary

## Acceptance Criteria
- [ ] Invoice contains links to workorder and estimate/approval trail.
- [ ] Authorized users can retrieve approval artifacts from invoice context.
- [ ] Issuance is blocked if traceability is incomplete (policy).

## Notes for Agents
Traceability is your defense in disputes; enforce it.


### Backend Requirements

- Implement Spring Boot microservices
- Create REST API endpoints
- Implement business logic and data access
- Ensure proper security and validation

### Technical Stack

- Spring Boot 3.2.6
- Java 21
- Spring Data JPA
- PostgreSQL/MySQL

---
*This issue was automatically created by the Durion Workspace Agent*
