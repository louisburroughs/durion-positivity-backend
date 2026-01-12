## 🏷️ Labels (Proposed)

### Required
- type:story
- domain:inventory
- status:needs-review

### Recommended
- agent:inventory
- agent:story-authoring

---
**Rewrite Variant:** inventory-flexible
---

## Story Intent

**As a** Receiver,
**I want to** initiate a new receiving session by referencing an existing Purchase Order (PO) or Advanced Shipping Notice (ASN),
**so that** the system can pre-populate the expected items and quantities, streamlining the check-in process and ensuring accuracy.

## Actors & Stakeholders

- **Actors:**
  - `Receiver`: The primary user performing the physical and systemic receipt of goods.
  - `System`: The POS/Inventory management system facilitating the process.
- **Stakeholders:**
  - `Inventory Manager`: Responsible for the overall accuracy and efficiency of inventory operations.
  - `Purchasing Manager`: Responsible for the procurement lifecycle, including PO creation and fulfillment.
  - `System (Positivity)`: An external system that may provide the ASN data.

## Preconditions

1. The `Receiver` is authenticated and has the necessary permissions to perform receiving functions.
2. The Purchase Order or ASN to be received against exists in the system and is in an `Open` or `Partially Received` state.
3. Product master data for all items listed on the source PO/ASN is available in the system.

## Functional Behavior

1. The `Receiver` navigates to the receiving module and initiates the "Create New Session" flow.
2. The `System` prompts the `Receiver` to provide an identifier for the source document (e.g., PO Number, ASN ID). The identifier can be provided via:
    - **Manual text entry** into an input field, or
    - **Barcode scan** (which populates the same input field automatically)
3. The `Receiver` provides the identifier using one of the supported methods.
4. The `System` validates the identifier against existing, receivable POs and ASNs using an exact match.
5. Upon successful validation, the `System` creates a new `ReceivingSession` entity with a default status of `Open`.
6. The `System` populates the new session with key information from the source document, including:
    - Supplier/Distributor reference.
    - Shipment reference number (if available, e.g., from an ASN).
    - A list of expected `ReceivingLine` items, copying the Product ID and expected quantity for each line from the source document.
7. The `System` records metadata about how the identifier was provided (`MANUAL` or `SCAN`).
8. The `System` then presents the newly created session to the `Receiver`, ready for the item check-in and quantity confirmation phase to begin (which is handled in a subsequent story).

## Alternate / Error Flows

- **Source Document Not Found:** If the `Receiver` provides an identifier that does not correspond to a known PO or ASN, the `System` must display a clear error message (e.g., "PO/ASN [Identifier] not found") and prevent session creation.
- **Source Document Already Received:** If the identifier corresponds to a PO or ASN that is already in a `Closed` or `Fully Received` state, the `System` must display an informative error message (e.g., "PO [Identifier] has already been fully received") and block session creation.
- **Blind Receiving Not Supported:** If the physical shipment arrives without a scannable or enterable PO/ASN reference, the `System` must not allow receiving session creation. A clear blocking message must be displayed: "Receiving requires a valid PO or ASN. Blind receiving is not supported." (Note: Blind receiving may be introduced in a future story as a separate workflow with a separate permission `ALLOW_BLIND_RECEIVING`.)
- **User Cancellation:** The `Receiver` can cancel the creation process at any point before the session is finalized, returning them to the main receiving screen without creating any new data.

## Business Rules

- A `ReceivingSession` must be tied to exactly one source document (either a PO or an ASN).
- The initial status of a newly created `ReceivingSession` must be `Open`.
- All `ReceivingLine` items created within the session must have their `receivedQuantity` initialized to zero.
- The system must support partial receipts. A PO/ASN can have multiple `ReceivingSession`s created against it over time, until its total expected quantities are fulfilled.
- **Blind receiving is explicitly not supported in this story.** A valid PO or ASN identifier is required to create a receiving session.
- This story covers the *creation* of the session only. The subsequent actions of counting items, matching them to lines, and capturing variances are explicitly out of scope and will be handled in a separate, subsequent user story (e.g., *Receiving: Perform Count and Capture Variances*).

## Data Requirements

- **`ReceivingSession`**
  - `sessionID`: (UUID, System-generated) - PK
  - `sourceDocumentID`: (String) - The identifier of the source PO or ASN (e.g., "PO-12345").
  - `sourceDocumentType`: (Enum: `PO`, `ASN`)
  - `supplierID`: (FK) - Reference to the Supplier entity.
  - `shipmentReference`: (String, Nullable)
  - `status`: (Enum: `Open`, `InProgress`, `Completed`, `Cancelled`)
  - `entryMethod`: (Enum: `MANUAL`, `SCAN`) - How the identifier was provided
  - `createdAt`: (Timestamp)
  - `createdByUserID`: (FK)
- **`ReceivingLine`**
  - `lineID`: (UUID, System-generated) - PK
  - `sessionID`: (FK) - Reference to `ReceivingSession`.
  - `productID`: (FK) - Reference to the Product Master.
  - `expectedQuantity`: (Decimal)
  - `receivedQuantity`: (Decimal, Default: 0)

## Acceptance Criteria

### AC1: Successful Session Creation from a Purchase Order via Manual Entry
- **Given** a `Receiver` is logged into the system with receiving permissions,
- **And** a Purchase Order with ID `PO-123` exists in an `Open` state, containing two item lines.
- **When** the `Receiver` initiates a new receiving session and manually enters the identifier `PO-123`.
- **Then** a new `ReceivingSession` is created with a `status` of `Open`.
- **And** the session is linked to the source document `PO-123`.
- **And** the session contains two `ReceivingLine` items that correspond to the items and expected quantities on `PO-123`.
- **And** the `entryMethod` is recorded as `MANUAL`.

### AC2: Successful Session Creation from an ASN via Barcode Scan
- **Given** a `Receiver` is logged into the system with receiving permissions,
- **And** an ASN with ID `ASN-ABC-789` exists in an `Open` state.
- **When** the `Receiver` initiates a new receiving session and scans the barcode for `ASN-ABC-789`.
- **Then** a new `ReceivingSession` is created with a `status` of `Open`.
- **And** the session is linked to the source document `ASN-ABC-789`.
- **And** the session's lines are pre-populated from the ASN data.
- **And** the `entryMethod` is recorded as `SCAN`.

### AC3: Failure When Source Document is Not Found
- **Given** a `Receiver` is on the "Create New Session" screen.
- **When** the `Receiver` enters an identifier `PO-999` which does not exist in the system.
- **Then** the system must display an actionable error message, such as "Source document PO-999 not found."
- **And** no `ReceivingSession` is created.

### AC4: Failure When Source Document is Already Closed
- **Given** a `Receiver` is on the "Create New Session" screen,
- **And** a Purchase Order `PO-456` exists but has a status of `Closed` or `Fully Received`.
- **When** the `Receiver` enters the identifier `PO-456`.
- **Then** the system must display an actionable error message, such as "PO-456 has already been fully received."
- **And** no `ReceivingSession` is created.

### AC5: Failure When Attempting Blind Receiving
- **Given** a `Receiver` is on the "Create New Session" screen.
- **When** the `Receiver` attempts to create a session without providing a valid PO or ASN identifier.
- **Then** the system must display a clear blocking message: "Receiving requires a valid PO or ASN. Blind receiving is not supported."
- **And** no `ReceivingSession` is created.

## Audit & Observability

- **Audit Trail:** An immutable audit event must be logged upon the successful creation of a `ReceivingSession`. The event payload must include `sessionID`, `creatorUserID`, `creationTimestamp`, `sourceDocumentID`, `sourceDocumentType`, and `entryMethod`.
- **Logging:**
  - `INFO`: Log successful session creation events with entry method details.
  - `WARN`: Log failed attempts to create a session due to business rule violations (e.g., PO not found, PO already closed, blind receiving attempt) for monitoring potential process or data integrity issues.

## Clarifications (Resolved)

The following questions were raised during story authoring and have been resolved via [clarification issue #232](https://github.com/louisburroughs/durion-positivity-backend/issues/232):

### Question 1: Identifier Method
**Decision:** The primary methods for providing the PO/ASN identifier are:
- **Manual text entry** into an input field
- **Barcode scan** (which populates the same input field)
- **Searchable list is explicitly out of scope** for this story and may be a follow-up enhancement.

**Enforcement:** Input must be an exact match against an existing PO or ASN. Validation occurs before session creation. The system records:
- `identifierType` (PO or ASN)
- `identifierValue` (the actual identifier)
- `entryMethod` (MANUAL or SCAN)

### Question 2: "Blind" Receiving
**Decision:** **Blocked** for this story.

If no scannable or enterable PO/ASN is available:
- The system must **not allow** receiving session creation
- Display blocking message: "Receiving requires a valid PO or ASN. Blind receiving is not supported."

**Future extensibility (explicitly out of scope):** Blind receiving may be introduced later as a separate workflow with a separate permission (`ALLOW_BLIND_RECEIVING`).

### Question 3: Scope of "Matching and Variances"
**Decision:** **Confirmed out of scope** for this story.

This story includes only:
- Identification of PO/ASN
- Validation of existence and eligibility  
- Creation of a Receiving Session shell with:
  - `receivingSessionId`
  - `sourceDocumentId`
  - `status = CREATED`

**Explicitly excluded (next story):**
- Counting physical items
- Line-by-line matching
- Recording over/under/incorrect items
- Variance approval workflows

These will be handled in a subsequent story (e.g., *Receiving: Perform Count and Capture Variances*).

---

## Original Story (Unmodified – For Traceability)
# Issue #35 — [BACKEND] [STORY] Receiving: Create Receiving Session from PO/ASN

## Current Labels
- backend
- story-implementation
- user

## Current Body
## Backend Implementation for Story

**Original Story**: [STORY] Receiving: Create Receiving Session from PO/ASN

**Domain**: user

### Story Description

/kiro
# User Story
## Narrative
As a **Receiver**, I want to create a receiving session from a PO/ASN so that inbound items can be checked in.

## Details
- Session includes supplier/distributor, shipment ref, expected lines.
- Support scanning barcodes and capturing lot/serial (optional v1).

## Acceptance Criteria
- Receiving session created.
- Lines can be matched and variances captured.
- Session auditable.

## Integrations
- Positivity may provide ASN; product master maps items.

## Data / Entities
- ReceivingSession, ReceivingLine, SupplierRef, VarianceRecord

## Classification (confirm labels)
- Type: Story
- Layer: Domain
- Domain: Inventory Management


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
