# Issue #147 — [BACKEND] [STORY] Invoicing: Preserve Traceability Links (Estimate/Approval/Workorder)

## Current Labels
- backend
- story-implementation
- user
- type:story
- blocked:domain-conflict
- status:needs-review

## Current Body
STOP: Conflicting domain guidance detected

## 🏷️ Labels (Proposed)
### Required
- type:story
- status:needs-review
- blocked:domain-conflict

### Recommended
- agent:story-authoring
- agent:billing
- agent:workexec

### Blocking / Risk
- blocked:domain-conflict

**Rewrite Variant:** integration-conservative

## ⚠️ Domain Conflict Summary
- **Candidate Primary Domains:** `domain:billing`, `domain:workexec`
- **Why conflict was detected:** The story requires the creation of an `Invoice` (a `domain:billing` artifact with its own state model) but the core logic is to enforce and immutably link to artifacts from `domain:workexec` (Workorder, Estimate, Approval). This splits the ownership of business rules and data authority between two domains.
- **What must be decided:** Which domain is the primary owner of this story? Is this a `workexec` story about *providing* traceability data for downstream consumers, or a `billing` story about *consuming and enforcing* traceability data during invoice creation?
- **Recommended split:** Yes. A clean separation would be:
    1.  **Story (workexec):** Expose a stable, versioned "Billable Work Summary" data contract from a completed Workorder.
    2.  **Story (billing):** Create an Invoice Draft by consuming the "Billable Work Summary", ensuring all traceability IDs are copied and stored immutably.

## Story Intent
**As a** Billing Administrator,
**I want** to generate a draft invoice from a completed workorder that automatically and immutably captures links to the authorizing estimate and customer approval,
**so that** every invoice is auditable, legally defensible, and has a clear lineage to the approved scope of work, reducing billing disputes and financial risk.

## Actors & Stakeholders
- **System (Primary Actor):** The automated process responsible for creating the invoice and embedding the traceability links.
- **Billing Administrator (User):** The user role that initiates invoice generation and relies on the traceability data for verification and resolving customer inquiries.
- **Auditor (Stakeholder):** A role that requires a clear, unbroken audit trail from work performed to final invoice.
- **Customer (Stakeholder):** The recipient of the invoice who benefits from transparency.

## Preconditions
- A `Workorder` exists and is in a `Completed` (or equivalent billable) state.
- The `Workorder` has a definitive, non-null reference to the specific `Estimate Version` that was approved.
- The `Workorder` has a definitive, non-null reference to one or more `Approval Records`.
- The initiating user or process has the necessary permissions to create invoices.

## Functional Behavior
### Trigger
A request is received to generate a `Draft Invoice` from a specific `Workorder ID`.

### Main Success Scenario
1.  The system receives a request to generate an invoice for a given `Workorder ID`.
2.  The system retrieves the `Workorder` and verifies it is in a billable state (e.g., `Completed`).
3.  The system verifies the presence of the required traceability links: `estimateVersionId` and `approvalRecordId(s)`.
4.  The system creates a new `Invoice` entity in a `Draft` state.
5.  The system populates the new `Invoice` with immutable references copied from the workorder:
    - `sourceWorkorderId`
    - `sourceEstimateVersionId`
    - `sourceApprovalRecordIds` (a list of one or more IDs)
6.  The system confirms the successful creation of the `Draft Invoice` and returns its unique identifier.

## Alternate / Error Flows
- **Flow 1: Source Workorder Not Billable**
    - If the referenced `Workorder` is not in a `Completed` (or other billable) state, the system rejects the request.
    - **Outcome:** An error response is returned with a code like `WORKORDER_NOT_BILLABLE`, and no invoice is created.

- **Flow 2: Missing Traceability Links**
    - If the `Workorder` is in a billable state but is missing a required reference (e.g., `estimateVersionId` or has no `approvalRecordIds`), the system rejects the request.
    - **Outcome:** An error response is returned with a code like `INCOMPLETE_TRACEABILITY_DATA`. A high-severity alert is logged for administrative review. No invoice is created.

- **Flow 3: Invalid Workorder ID**
    - If the provided `Workorder ID` does not correspond to an existing record, the system rejects the request.
    - **Outcome:** A `404 Not Found` error is returned.

## Business Rules
- **BR-1: Billable State Prerequisite:** An `Invoice` may only be generated from a `Workorder` that is in a terminal, billable state (e.g., 'Completed', 'CustomerAccepted'). This state list must be configurable.
- **BR-2: Traceability Completeness:** A `Workorder` is considered to have complete traceability for invoicing purposes if and only if it has non-null references to both an `Estimate Version` and at least one `Approval Record`.
- **BR-3: Link Immutability:** Once an `Invoice` is created, its traceability links (`sourceWorkorderId`, `sourceEstimateVersionId`, `sourceApprovalRecordIds`) are immutable and cannot be modified.
- **BR-4: Issuance Gating:** Company policy dictates that an invoice cannot be moved from `Draft` to `Issued` status unless all required traceability links are present and valid.

## Data Requirements
### Entity: `Invoice`
This story requires the `Invoice` entity to contain the following fields to store the traceability links.

| Field Name                  | Data Type           | Constraints                               | Description                                                      |
| --------------------------- | ------------------- | ----------------------------------------- | ---------------------------------------------------------------- |
| `invoiceId`                 | UUID                | Primary Key                               | Unique identifier for the invoice.                               |
| `status`                    | Enum                | `DRAFT`, `ISSUED`, `PAID`, `VOID`         | The current lifecycle state of the invoice.                      |
| `sourceWorkorderId`         | UUID                | Not Null, Immutable, Foreign Key          | The ID of the `Workorder` this invoice was generated from.       |
| `sourceEstimateVersionId`   | UUID                | Not Null, Immutable, Foreign Key          | The ID of the specific `Estimate Version` that was approved.     |
| `sourceApprovalRecordIds`   | Array of UUID       | Not Null, Not Empty, Immutable, Foreign Key | A list of one or more `Approval Record` IDs that authorize the work. |

## Acceptance Criteria
- **AC-1: Successful Invoice Draft with Full Traceability**
    - **Given** a `Workorder` is in a 'Completed' state
    - **And** it has a valid reference to an `Estimate Version` and an `Approval Record`
    - **When** the system receives a request to generate an invoice for that `Workorder`
    - **Then** a new `Invoice` is created in the 'Draft' state
    - **And** the `Invoice` record contains the immutable IDs of the source `Workorder`, `Estimate Version`, and `Approval Record`.

- **AC-2: Block Invoice Generation for Incomplete Workorder**
    - **Given** a `Workorder` is in an 'In-Progress' state
    - **When** the system receives a request to generate an invoice for that `Workorder`
    - **Then** the request is rejected with an error indicating the `Workorder` is not in a billable state.
    - **And** no `Invoice` record is created.

- **AC-3: Block Invoice Generation for Missing Traceability Links**
    - **Given** a `Workorder` is in a 'Completed' state but is missing a reference to an `Approval Record`
    - **When** the system receives a request to generate an invoice for that `Workorder`
    - **Then** the request is rejected with an error indicating incomplete traceability data.
    - **And** a high-severity alert is logged for administrative action.

## Audit & Observability
- **AUD-1:** On successful draft invoice creation, an `INFO` level event must be logged with structured context including `invoiceId`, `sourceWorkorderId`, `sourceEstimateVersionId`, and `sourceApprovalRecordIds`.
- **AUD-2:** On any failed invoice creation attempt, a `WARN` level event must be logged with the `WorkorderId` and a structured `reason` code (e.g., `INCOMPLETE_TRACEABILITY_DATA`, `WORKORDER_NOT_BILLABLE`).
- **MET-1:** A metric counter `invoice.creation.attempts` shall be incremented for every request.
- **MET-2:** A metric counter `invoice.creation.failures` shall be incremented on failure, tagged by `reason`.

## Open Questions
- **OQ-1 (Domain Ownership):** This story spans `domain:workexec` (the source of truth for work) and `domain:billing` (the owner of the invoice). As flagged in the conflict summary, a decision is needed on the primary domain owner and the inter-domain contract to avoid tight coupling. Which team is responsible for defining and enforcing the "billable" state and data contract?
- **OQ-2 (UI/Presentation):** The original story mentions "exposes traceability in UI" and "includes reference identifiers in customer-facing invoice where configured." Are these presentation-layer requirements in scope for this backend story, or will they be handled in separate UI and document-generation stories?

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

- Spring Boot 4.0.2
- Java 21
- Spring Data JPA
- PostgreSQL/MySQL

---
*This issue was automatically created by the Durion Workspace Agent*
