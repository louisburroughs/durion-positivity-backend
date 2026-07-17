# Cross-Domain Workflow Playbooks

## Purpose
RAG id: `workflow.cross-domain-playbooks`  
RAG scope: `master`  
Required permissions: `AUTHENTICATED`  
Audience: internal staff.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document describes common operational flows that cross service boundaries. It is intended for retrieval when a user asks "what happens next," "why is this stuck," or "what depends on what" across estimates, workorders, inventory, invoicing, payment, purchasing, receiving, reconciliation, warranty, and claims.

## Playbook: estimate to invoice to payment
The normal service flow begins with a customer, vehicle, and service need. A service advisor creates or reviews an estimate with parts and labor. The estimate should be treated as the customer-facing proposal until it is approved. The existing shop guide states that an estimate is an itemized list of parts and labor created before work begins and must be approved with customer signature before promotion to a workorder.

Hand-offs:

1. Customer/vehicle context supplies the party, contact, vehicle, account, and service history identifiers.
2. Pricing supplies price-book and rule context for parts, labor, fees, and discounts.
3. Inventory supplies availability, reservations, pick lists, and consumption events for parts.
4. Shop scheduling supplies appointment, bay or mobile-unit assignment, mechanic assignment, and conflict status.
5. Workorder execution supplies active work state, labor entries, parts usage, quality/completion status, and change requests.
6. Invoice processing supplies invoice generation and finalization.
7. Accounting/payment processing supplies receivable, revenue, cash, and reconciliation context.

Blocking questions the assistant should answer: Is there an approved estimate? Is the appointment valid? Are parts available or reserved? Are labor and parts complete? Is there an unapproved change request? Has the workorder reached a state that can generate an invoice? Has the invoice been finalized or paid?

## Playbook: change request during active service
A change request is used when additional work is discovered after the original estimate. The current shop RAG material defines a change request as work added to an in-progress workorder that was not on the original estimate and that requires customer approval before proceeding.

Operational steps:

1. Technician or service advisor identifies additional work.
2. The added work is described as parts, labor, reason, and affected workorder line context.
3. Pricing and tax implications are recalculated or flagged for recalculation.
4. Customer approval is captured before the added work proceeds, unless an emergency override rule exists and can be verified.
5. Approved change-request lines become part of the workorder execution record.
6. Parts are reserved or picked; labor is performed and recorded.
7. Invoice generation should reflect the approved final work scope.

The assistant should distinguish between "recommended work," "approved work," and "performed work." If approval state is unknown, the assistant must not imply the work can proceed.

## Playbook: purchase order to receipt to reconciliation
The purchasing flow connects order, inventory, and accounting. The purchasing record identifies what was ordered, from whom, for which location, and at what expected cost. Receiving converts ordered or in-transit goods into on-hand inventory through a goods receipt or purchase-order receiving action. Accounting then reconciles supplier invoice, receipt, and payable context.

Hand-offs:

1. Order domain creates the purchase order or order lines.
2. Inventory receives the goods, records quantities, location, lot/serial context when applicable, and updates on-hand state.
3. Inventory exceptions such as short receipt, damaged goods, wrong SKU, or UoM mismatch must be explicit and auditable.
4. Accounting/AP compares ordered quantity, received quantity, and supplier invoice amount.
5. Reconciliation should not assume a match when quantity, SKU, location, cost, or supplier reference differs.

The assistant should answer: What was ordered? What was received? What remains open? Which receipt created the on-hand increase? What AP item or vendor invoice is waiting? Where does the mismatch occur?

## Playbook: warranty claim
Warranty claims are owned by `pos-warranty` and connect customer, vehicle, workorder, invoice, inventory, and accounting. A claim is identified by its claim code `WC-{yyyy}-{seq}` (see the glossary) and moves through the states DRAFT, SUBMITTED, IN_REVIEW, INFO_NEEDED, APPROVED, DENIED, SETTLED, CLOSED, CANCELLED. The design is customer-first: the customer is made whole at settlement; vendor reimbursement and defective-part return are back-office child lifecycles that follow.

Operational steps:

1. Intake creates a DRAFT claim: customer is validated against `pos-customer`, vehicle context (VIN, odometer) is snapshotted from `pos-vehicle-inventory`, and product/manufacturer context comes from `pos-catalog`.
2. Origin search: the candidate-lines lookup searches `pos-invoice` invoice lines and `pos-workorder` parts/service lines by customer, vehicle, and SKU to link the claim to the original sale or service.
3. Submit runs the eligibility evaluation and moves DRAFT to SUBMITTED (or INFO_NEEDED to IN_REVIEW). The eligibility result is a suggestion only and can be re-run on demand; it never auto-decides.
4. Decision is human adjudication: IN_REVIEW to APPROVED or DENIED. An appeal is modeled as DENIED back to IN_REVIEW with a mandatory reason.
5. Settlement makes the customer whole first. Settlement types: REPLACEMENT_WORKORDER (a replacement workorder is created through the normal estimate/workorder flow and linked from the claim via `replacementWorkorderId` — `pos-warranty` never writes to `pos-workorder`), INVOICE_CREDIT / PRORATED_CREDIT / REFUND (invoice adjustment or refund created in `pos-invoice`, carrying the settlement id as `externalReference` for idempotent correlation), GOODWILL, or NO_ACTION. Settlement moves the claim to SETTLED and emits `warranty.claim.settled` (published for accounting/reporting; no consumer of this event is implemented yet — the assistant must not describe a `warranty.claim.settled` consumer as live).
6. Back-office follow-up: vendor/provider reimbursement (`warranty.reimbursement.submitted` and `warranty.reimbursement.resolved`, consumed live by `pos-accounting` into its `warranty_reimbursement_expectation` table for expected-credit matching — status `EXPECTED` on submission, then the terminal `ReimbursementStatus` such as `APPROVED`, `DENIED`, or `WRITTEN_OFF` on resolution) and defective-part return RMA (`warranty.part-return.requested` and `warranty.part-return.shipped`, consumed live by `pos-inventory` into its `warranty_part_return_hold` table — status `REQUESTED` then `SHIPPED` with carrier/tracking; the physical quarantine shelf remains a manual process, the table is the tracking record). Both consumers are live per issue #927 (PRD §9.3).
7. Close: SETTLED to CLOSED once reimbursement and part-return follow-ups are resolved.
8. Read replicas: `warranty.claim.snapshot` carries the full claim aggregate for replica builders, emitted on every claim mutation (ADR-0044 R3), so other modules can mirror claims without calling `pos-warranty`. No snapshot consumer is implemented yet — the assistant must not describe a `warranty.claim.snapshot` replica as live.

Permissions are `warranty:*`: claim actions are `warranty:claim:create/view/submit/decide/settle/cancel/close`; supporting resources use view/manage pairs — `warranty:policy:*`, `warranty:provider:*`, `warranty:registration:*`, `warranty:reimbursement:*`, `warranty:part-return:*`.

Blocking questions the assistant should answer: Is there a linked origin line (invoice or workorder)? What did eligibility suggest and why? Has a human decided the claim? Which settlement type was used, and does the invoice adjustment/refund reference the settlement id? Is vendor reimbursement submitted/resolved? Is the part return requested/shipped? Can the claim be closed?

_Verified: `pos-warranty` `ClaimController` (intake, candidate-lines, submit, eligibility re-run, decide), `SettlementController`/`SettlementServiceImpl` (`warranty.claim.settled`), `ReimbursementServiceImpl` (`warranty.reimbursement.*`), `PartReturnServiceImpl` (`warranty.part-return.*`), `ClaimStatus`/`SettlementType` enums, `InvoiceClientImpl` (`externalReference` = settlement id), `WarrantyPermissionRegistration`, `pos-accounting` `WarrantyEventsListener` (`warranty_reimbursement_expectation`, group `pos-accounting-warranty-events`), `pos-inventory` `WarrantyEventsListener` (`warranty_part_return_hold`, group `pos-inventory-warranty-events`). No consumer exists for `warranty.claim.settled` or `warranty.claim.snapshot`. PRD: `docs/PRD-warranty-claims-module.md`._

## Interpreting blocked cross-domain requests
When a user asks why a workflow is blocked, the assistant should identify the current entity and then test dependencies in order. For a workorder-to-invoice issue, check approval, workorder status, parts/labor completeness, change-request approval, and invoice generation/finalization. For PO-to-reconciliation, check PO lines, receipt lines, quantity/cost match, vendor invoice, and AP state. For a warranty claim, check claim state, linked origin line, eligibility result, human decision, settlement completion, and open reimbursement/part-return follow-ups.

The assistant should not invent API calls or tool names. It should describe the capability, required identifiers, and permission context unless an operation has been verified against an OpenAPI source.
