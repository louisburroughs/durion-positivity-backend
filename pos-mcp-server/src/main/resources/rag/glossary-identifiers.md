# Glossary and Identifier Formats

## Purpose
RAG id: `glossary.identifiers`  
RAG scope: `master`  
Required permissions: `AUTHENTICATED`  
Audience: all internal staff.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document defines common terms, abbreviations, and identifiers for natural-language retrieval. Identifier formats are intentionally conservative. When a format has not been verified against a module source, the assistant should ask for the exact identifier as entered in the system instead of guessing.

## WO / workorder
`WO` means workorder. Workorder is one word in Durion Positivity documents. A workorder is the active record that tracks a vehicle being serviced after an approved estimate is promoted. A workorder may include labor entries, parts usage, technician assignment, change requests, and invoice generation context.

## Workorder number
A workorder number identifies a workorder record. The exact canonical format is not verified in the bundle. Staff may say "WO," "workorder," "job," or "ticket." The assistant should accept the user's spelling, ask for the exact workorder number when ambiguous, and use lexical retrieval for exact-code recall.

> TODO(verify): canonical workorder number format from pos-workorder source or API schema.

## Estimate
An estimate is an itemized list of parts and labor created for a customer before work begins. The existing shop guide states that it must be approved with customer signature before promotion to a workorder. Estimate questions often involve approval, pricing, parts, labor, tax, and conversion to workorder.

## Change request
A change request adds work to an in-progress workorder that was not on the original estimate. It requires customer approval before proceeding, according to the existing shop guide. Staff may ask "add-on," "extra work," or "new work found." The assistant should separate requested, approved, and performed work.

## SKU
`SKU` means Stock Keeping Unit. It is the product identifier used for inventory, pricing, parts lines, purchasing, and pick lists. The exact SKU pattern is not verified in the bundle. Exact SKU queries should use lexical retrieval because dense embeddings can under-retrieve codes.

> TODO(verify): canonical SKU format from product/catalog or inventory source.

## VIN
`VIN` means Vehicle Identification Number. It identifies a vehicle and is relevant to customer/vehicle lookup, service history, warranty or claim context, and workorder context. The bundle names VIN as an identifier but does not verify validation rules. The assistant should avoid validating VIN structure unless the vehicle service source confirms it.

> TODO(verify): VIN validation and storage rules from pos-customer or vehicle API schema.

## Invoice number
An invoice number identifies an invoice or billing document. The bundle verifies invoice permissions only at coarse levels (`invoice:manage`, `invoice:finalize`) and explicitly notes no `invoice:read` sample. The assistant should not fabricate invoice-read permissions or invoice-number format rules.

> TODO(verify): invoice number format and read permission from pos-invoice source.

## PO number
`PO` means purchase order. A PO identifies ordered goods or services before receipt. PO-to-receipt-to-reconciliation flows connect order, inventory, goods receipt, AP, and accounting. The exact PO number pattern is not verified in the bundle.

> TODO(verify): canonical PO number format from pos-order or inventory source.

## Account code
An account code may refer to a commercial account, billing account, accounting account, or claim/account reference depending on context. The assistant should clarify the domain when a user says only "account code." Accounting account codes should not be confused with customer account identifiers.

> TODO(verify): account-code formats by domain.

## Claim code
A claim code is referenced in the Gate 5 prompt as an exact identifier for lexical retrieval. The bundle does not provide claim-service rules or a verified claim-code format. The assistant should ask for the full code and avoid stating claim eligibility rules without source grounding.

> TODO(verify): claim-code format and claim workflow source.

## Appointment
An appointment is a scheduled service visit linking a customer, a vehicle, a time slot, and one or more shop resources such as a bay or mobile unit. It can move through statuses including scheduled, checked in, work in progress, waiting for parts, quality check, ready for pickup, completed, cancelled, invoiced, and reopened.

## Bay
A bay is a fixed service stall at a location. Bays are schedulable resources. Appointment assignments can link a bay to an appointment and mechanics. Bay questions often involve availability, conflict detection, and location schedule.

## Mobile unit
A mobile unit is a vehicle-based service resource that can perform work on site at a customer location. The existing shop guide treats mobile units like bays for scheduling. It has a base location and declared service capabilities.

## WIP
`WIP` means Work In Progress. It refers to the live dashboard or view of active workorders at a location. WIP questions often ask what is open, blocked, waiting for parts, assigned, or ready for quality check.

## Pick list
A pick list identifies inventory items that need to be pulled from stock to fulfill parts lines on a workorder. Pick-list context connects workorder parts to inventory availability, reservation, issue, and consumption.

## Goods receipt
A goods receipt records received inventory. It is used when goods arrive from a supplier or purchase order and should update stock state audibly and consistently with accounting.

## Idempotency key
An idempotency key prevents duplicate creation for repeated submissions. The existing shop guide states appointment creation supports an `Idempotency-Key` header so submitting the same key twice returns the original result rather than creating a duplicate.
