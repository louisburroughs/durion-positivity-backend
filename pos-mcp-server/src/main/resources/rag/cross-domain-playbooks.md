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

## Playbook: warranty or claim
Warranty and claim flows connect customer, vehicle, workorder, invoice, inventory, and accounting. The bundle verifies account/claim codes as identifiers to include in the glossary, but it does not provide a complete claim-service rule source. Any rule about claim eligibility, manufacturer approval, reimbursement timing, or claim-code format must therefore be marked for verification.

Typical hand-offs:

1. Customer/vehicle context identifies owner, service history, VIN, unit, or account.
2. Workorder context identifies the performed service, parts, labor, dates, and technician notes.
3. Invoice context identifies billed amounts and whether the customer, fleet, warranty provider, or manufacturer is responsible.
4. Inventory context may identify part SKU, lot, serial, or return disposition.
5. Accounting context identifies receivable, adjustment, credit, or reimbursement treatment.

> **VERIFIED gap:** No warranty / claim / RMA owner was found in `pos-invoice`, `pos-order`, or `pos-inventory` (inventory owns customer *returns* via `inventory:return:*`, distinct from warranty claims; invoice owns payments/refunds, not claims). There is no verified claim-code schema, claim approval state machine, or reimbursement workflow in these services. The assistant must NOT describe a warranty/claim flow as if it exists. TODO(verify): confirm whether any other service owns warranty/claims before authoring this playbook further.

## Interpreting blocked cross-domain requests
When a user asks why a workflow is blocked, the assistant should identify the current entity and then test dependencies in order. For a workorder-to-invoice issue, check approval, workorder status, parts/labor completeness, change-request approval, and invoice generation/finalization. For PO-to-reconciliation, check PO lines, receipt lines, quantity/cost match, vendor invoice, and AP state. For warranty/claim, check whether the claim source is verified before stating a business rule.

The assistant should not invent API calls or tool names. It should describe the capability, required identifiers, and permission context unless an operation has been verified against an OpenAPI source.
