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
A workorder number is the human-facing identifier of a workorder, distinct from its UUID `id`. Format: `WO-YYYY-NNNN`, sequence starting at 1000 (e.g. `WO-2026-1001`). On promotion from an estimate it reuses the estimate's number with the prefix swapped (`EST-YYYY-NNNN` → `WO-YYYY-NNNN`) when free, else a fresh `WO-<year>-<seq>`. Estimate numbers are `EST-YYYY-NNNN`, unique per location. Staff may say "WO," "workorder," "job," or "ticket"; use lexical retrieval for exact-code recall.

_Verified: `pos-workorder` `Workorder.java` (human-readable `workorderNumber`, unique) + `WorkorderServiceImpl.generateWorkorderNumber`; `Estimate`/`EstimateServiceImpl.generateEstimateNumber`._

## Estimate
An estimate is an itemized list of parts and labor created for a customer before work begins. The existing shop guide states that it must be approved with customer signature before promotion to a workorder. Estimate questions often involve approval, pricing, parts, labor, tax, and conversion to workorder.

## Change request
A change request adds work to an in-progress workorder that was not on the original estimate. It requires customer approval before proceeding, according to the existing shop guide. Staff may ask "add-on," "extra work," or "new work found." The assistant should separate requested, approved, and performed work.

## SKU
`SKU` means Stock Keeping Unit — the product identifier used across inventory, pricing, parts lines, purchasing, and pick lists. In `pos-catalog` the SKU is a client-supplied string, unique (`uk_product_sku`), required at create, and immutable thereafter. There is no system-enforced length or regex pattern and no auto-generation; example values like `SKU-12345` are illustrative only. Treat the SKU as an opaque exact code — use lexical retrieval since dense embeddings under-retrieve codes.

_Verified: `pos-catalog` `ProductEntity` (`sku` unique), `ProductCreateRequestDto` (`@NotBlank`, no `@Pattern`/`@Size`), `ProductMasterDataServiceImpl` ("sku is immutable")._

## VIN
`VIN` means Vehicle Identification Number. The system of record is `pos-vehicle-inventory`. A VIN is exactly 17 characters matching `^[A-HJ-NPR-Z0-9]{17}$` — uppercase letters and digits with I, O, Q excluded. It is normalized (trim, uppercase, strip non-alphanumerics) and stored both raw (`vin`) and normalized (`vin_normalized`, globally unique). Use lexical retrieval for VIN lookups.

_Verified: `pos-vehicle-inventory` `VinUtils` (pattern `^[A-HJ-NPR-Z0-9]{17}$`, `INVALID_CHARS="IOQ"`) + `VehicleRecord` (`vin`/`vin_normalized` length 17)._

## Invoice number
An invoice number is the human-facing invoice identifier (distinct from the UUID `id`). Format: `INV-<epochMillis>-<first 8 chars of the invoice UUID>`, assigned once at draft creation. Reading an invoice is gated by `invoice:manage` — there is no `invoice:read` permission. (DTO `@Schema` examples like `INV-2026-1001` are illustrative and do NOT match the runtime format.)

_Verified: `pos-invoice` `InvoiceServiceImpl.generateInvoiceNumber()` (`"INV-"+epochMilli+"-"+idPart`); `InvoiceController` class-level `@PreAuthorize('invoice:manage')` covers GET._

## PO number
`PO` means purchase order. POs are owned by `pos-inventory` (NOT pos-order). The PO number is generated from a Postgres sequence rendered as an 8-character, zero-padded, uppercase base-36 code (e.g. `0000000A`) — there is no `PO-`/year prefix. (The OpenAPI example `PO-2026-00042` is aspirational and does NOT match runtime.) A goods-receipt number is `GR-<first 8 chars of a UUID>`. PO→receipt→reconciliation connects inventory, goods receipt, AP, and accounting.

_Verified: `pos-inventory` `PurchaseOrderServiceImpl.generatePoNumber()` (base-36 sequence) + `inventory:purchase_order:*` perms; `AsnServiceImpl.generateReceiptNumber()` (`GR-`+UUID8)._

## Account code
"Account code" is domain-dependent — clarify which is meant:
- **GL / chart-of-accounts code** (`pos-accounting`): matches `^\d{4}(-\d{3})?$` — four digits, optionally `-` plus three digits (e.g. `1000`, `4000-100`). Stored in `account_code` (length 20, unique).
- **Customer account number** (`pos-customer`): the party `customerNumber` (unique per party), not a GL code.
Journal entries have no human-facing number (UUID `journalEntryId` + integer `lineNumber` starting at 1).

_Verified: `pos-accounting` `GLAccountCreateRequest` (`@Pattern "^\\d{4}(-\\d{3})?$"`), `GLAccount`/`JournalEntry`; `pos-customer` `AbstractParty.customerNumber`._

## Claim code
A claim code is the human-facing identifier of a warranty claim, owned by `pos-warranty` (distinct from the claim's UUIDv7 `id`, which remains the primary key). Format: `WC-{yyyy}-{seq}` where the sequence is zero-padded to 6 digits and resets each calendar year (e.g. `WC-2026-000123`). The `claimCode` is unique and is the business/search key — claim lookups and cross-service references (e.g. invoice adjustments/refunds carry it as `externalReference`) use the claim code, while APIs address the claim by UUID. Use lexical retrieval for exact-code recall.

_Verified: `pos-warranty` `ClaimCodeServiceImpl` (`String.format("WC-%d-%06d", year, seq)`), `ClaimCodeSequence` (per-year counter), `WarrantyClaim.claimCode` (unique) + `WarrantyClaimRepository` claim-code lookup._

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
