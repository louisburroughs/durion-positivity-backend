# Warranty Claims Guide

## Purpose
RAG id: `warranty.guide`
RAG scope: `warranty`
Required permissions: `warranty:claim:view`
Audience: internal staff.
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document grounds warranty-domain questions for the natural-language assistant: how a warranty
claim is created, adjudicated, settled, and closed in `pos-warranty`, and how providers, policies,
and registrations govern eligibility. It describes the `pos-warranty` module (PRD §1–§9). It does
not define customer-facing marketing terms, pricing of extended-plan sales, or any behavior not
implemented below, and it must not be used to infer such behavior.

## Core concepts
A **warranty provider** funds a program: `MANUFACTURER`, `DISTRIBUTOR_PROGRAM`, `THIRD_PARTY_ADMIN`,
or `DEALER` (self-funded — dealer workmanship or dealer-funded road hazard). A **warranty policy**
is one program's structured coverage terms: a `coverageType` (`MANUFACTURER_DEFECT`,
`DEALER_WORKMANSHIP`, `ROAD_HAZARD`, `EXTENDED_PLAN` — the same four values as claim type), an
`appliesToType` scope (`PRODUCT_LIST`, `CATEGORY`, `MANUFACTURER`, or `ALL`, most specific wins), an
effectivity window (`effectiveFrom`/`effectiveTo`), and optional `requiresPhotoEvidence` /
`requiresPartReturn` flags. A **warranty registration** is sold/instantiated coverage tying a policy
to a customer (and optionally a purchase), with lifecycle `ACTIVE`, `EXPIRED`, `CANCELLED`,
`EXHAUSTED`. Registrations can be created automatically: `AutoRegistrationServiceImpl` registers a
customer for any `PRODUCT_LIST` policy with `autoRegister=true` whose covered products were sold on
an invoiced workorder, keyed off the workorder's sale date and skipping products already registered
against that invoice.

A **warranty claim** is the unit of adjudication: one claim per customer/vehicle failure, with one
or more claim lines (each sourced from an invoice line, workorder part, workorder service, or a
manual entry — `LineSourceType`). At intake the claim snapshots the vehicle's VIN and odometer from
the event-fed vehicle replica (frozen even if upstream data later changes), and records whether the
original sale is verified (`originWorkorderId`/`originInvoiceId` present) or is an
`originUnverified` walk-in/manual claim. Claims are identified by a human-facing **claim code**
`WC-{yyyy}-{seq}` (zero-padded, resets yearly) in addition to their UUID primary key — see
`glossary-identifiers.md` for the general identifier note; the exact allocation mechanics are
repeated here because this document is warranty-scoped.

The assistant should ask which claim type applies (policy-backed vs. goodwill), which policy or
provider governs it, which settlement path is intended, and whether reimbursement or part-return
work must stay open, whenever a user asks a broad "how do I file/settle a warranty claim?" question.

## Claim code format
A claim code is allocated by `ClaimCodeServiceImpl.nextClaimCode()`: `String.format("WC-%d-%06d",
year, seq)`, e.g. `WC-2026-000123`. The allocator locks that year's `claim_code_sequence` row
(`SELECT ... FOR UPDATE`) and increments `next_seq`, so allocation is atomic on both H2 and Postgres;
the sequence resets to 1 at the start of each new year. `claimCode` is unique and is the
business/search key for claim lookup (`GET /v1/warranty/claims?claimCode=`), while the API otherwise
addresses the claim by its UUID.

## The claim state machine (settle-customer-first)
`ClaimStatus` has nine values: `DRAFT, SUBMITTED, IN_REVIEW, INFO_NEEDED, APPROVED, DENIED, SETTLED,
CLOSED, CANCELLED`. Legal transitions (`ClaimStateMachine`):

```
DRAFT       → SUBMITTED | CANCELLED
SUBMITTED   → IN_REVIEW | CANCELLED
IN_REVIEW   → APPROVED | DENIED | INFO_NEEDED
INFO_NEEDED → IN_REVIEW | CANCELLED
APPROVED    → SETTLED
DENIED      → IN_REVIEW (appeal, reason required) | CLOSED
SETTLED     → CLOSED
CLOSED / CANCELLED → terminal
```

Intake edits — `PUT` update, add/remove line, add/remove photo — are allowed only while the claim is
`DRAFT` or `INFO_NEEDED`; any other status returns a 409 with `nextAction` listing the legal moves.
Submitting a claim runs eligibility first, then moves `DRAFT→SUBMITTED` or `INFO_NEEDED→IN_REVIEW`.
A decision of `APPROVE`/`DENY`/`REQUEST_INFO` on a `SUBMITTED` claim implicitly begins review
(`SUBMITTED→IN_REVIEW`) before applying. `APPEAL` is only legal from `DENIED` and requires a reason;
it reopens `DENIED→IN_REVIEW` and clears the prior decision (denial stays in the audited status
history).

Vendor reimbursement (§3.7) and part return / RMA (§3.8) are separate **child lifecycles** keyed by
`claimId`, not claim states. They never block `APPROVED→SETTLED` (settle-customer-first: the
customer is made whole as soon as the claim is approved, independent of vendor money or the
physical part). They **do** block `SETTLED→CLOSED`: closing a claim requires every vendor
reimbursement to be in a terminal state (`CREDIT_RECEIVED`, `WRITTEN_OFF`, `NOT_APPLICABLE`,
`DENIED`) and every part return to be in a terminal state (`RECEIVED_BY_VENDOR`, `SCRAPPED`,
`CLOSED`); otherwise `close` fails with `WARRANTY_CLAIM_CLOSE_BLOCKED`. When the governing policy's
`requiresPartReturn` flag is set, an approved-and-settled claim additionally cannot close until at
least one part return has been opened for it (a `DENIED→CLOSED` claim skips this — nothing was
covered).

## Eligibility evaluation ("suggest, don't dictate")
`EligibilityServiceImpl.evaluate` computes a **suggestion**, never a binding decision. It matches
candidate policies most-specific-first (`PRODUCT_LIST > MANUFACTURER > CATEGORY > ALL`) among
policies in effect on the claim's `originSaleDate` whose `coverageType` matches the claim's
`claimType`. Each structured term produces a reason map `{term, required, actual, pass}` — `pass`
is `null` (and the term indeterminate) when a needed fact is missing, e.g. product facts unavailable
because pos-catalog could not be reached, or a specificity tie between two equally-specific
candidate policies (never arbitrarily tie-broken). The overall `EligibilityResult` is one of
`ELIGIBLE`, `INELIGIBLE`, `INDETERMINATE`. Per-line proration uses one of `ProrationMethod` `NONE,
TREAD_DEPTH, MILEAGE, TIME` to compute a credit percentage/amount; a missing proration input (e.g.
no tread reading) keeps the line's amount unknown rather than suggesting a false `$0`. The
suggestion (`eligible`, `policyId`, `providerId`, `settlementType`, `totalRequested`, `perLine`) is
persisted on the claim, but the human decision stays with staff.

The human decision can override the computed suggestion. On `decide`, an optional `lineDecisions`
list records per-line `amountApproved`/disposition; an `amountApproved` that differs from the
computed `amountRequested` requires an `overrideReason`, which is written as an audited claim note.
Likewise `DENY` and `APPEAL` require a `reason`. Claim-level fields are `ClaimDecision`
(`APPROVE`, `DENY`, `REQUEST_INFO`) and per-line `LineDisposition` (`PENDING`, `APPROVED`,
`PARTIALLY_APPROVED`, `DENIED`, `WITHDRAWN`).

## Claim types and origin verification
`ClaimType` has four values: `MANUFACTURER_DEFECT`, `DEALER_WORKMANSHIP`, `ROAD_HAZARD`,
`EXTENDED_PLAN` — the same four values model both policy-backed claims (a matching `WarrantyPolicy`
of that `coverageType` governs) and goodwill/no-policy claims (no policy matches; the claim can
still be adjudicated and settled, typically via `GOODWILL` settlement). A claim's origin is
`originUnverified=true` when created without an `originWorkorderId` or `originInvoiceId` (a walk-in
or manually entered claim); `EligibilityServiceImpl` surfaces this as an `originVerified` reason term
so an unverifiable origin degrades the suggestion rather than silently passing.

`CandidateLineServiceImpl` supports origin-line search across pos-invoice and pos-workorder (via
event-fed `ext_*` replicas, ADR-0044): workorder parts match by `productEntityId` directly; invoice
lines and workorder service lines (no product reference) match by description text against the
catalog SKU/name. Each source degrades independently — a read failure on one still returns the
other's results.

## Settlement paths
`SettlementType` has six values: `REPLACEMENT_WORKORDER, INVOICE_CREDIT, REFUND, PRORATED_CREDIT,
GOODWILL, NO_ACTION`. A settlement may be created while the claim is `APPROVED` (the first
settlement moves the claim to `SETTLED`) or already `SETTLED` (a later, supplementary settlement).
`SettlementStatus` is `PENDING, COMPLETED, FAILED`.

- `REPLACEMENT_WORKORDER` is link-only: pos-warranty validates the given workorder id exists via the
  event-fed workorder replica and stores the reference; pos-workorder is never written to and stays
  unaware of warranty (no synchronous write, ADR-0044).
- `INVOICE_CREDIT`, `PRORATED_CREDIT`, and `GOODWILL` all call `InvoiceClient.createAdjustment`
  (`POST /v1/invoices/{invoiceId}/adjustments`, adjustment type `WARRANTY`) against pos-invoice.
- `REFUND` calls `InvoiceClient.createRefund` (`POST
  /v1/invoices/{invoiceId}/payments/{paymentId}/refunds`), reason `OTHER`; a non-`COMPLETED` refund
  status from pos-invoice (gateway declined) throws, so the customer is never marked refunded on a
  declined gateway response.
- `NO_ACTION` completes immediately with no downstream write.

A settlement's `PENDING` row is durably persisted (its own `REQUIRES_NEW` transaction) **before**
any money-moving call, so a trace of the attempt survives even if the outer transaction later rolls
back; on a `WarrantyIntegrationException` from pos-invoice the settlement is marked `FAILED` and the
error propagates so staff can retry with a fresh settlement. Settling the same claim concurrently is
serialized with a pessimistic row lock on the claim, taken before any money moves.

**Idempotency/correlation detail:** the `externalReference` value pos-invoice dedupes adjustments
and refunds on is the **settlement row's own UUID** (`settlement.getId().toString()`), not the
claim code string — each settlement is its own idempotency key. The claim code is embedded instead
in the human-readable adjustment reason text ("Warranty claim {claimCode} {settlementType}
settlement") and in the `warranty.claim.settled` outbox event payload. `SettlementReconciliationServiceImpl`
reconciles by re-fetching pos-invoice's adjustment/refund list and matching on that same
settlement-id `externalReference`.

## Vendor reimbursement and part return (post-settlement work)
A **vendor reimbursement** (`VendorReimbursement`, at most one per claim) is the back-office
vendor-money lifecycle: `ReimbursementStatus` is `NOT_SUBMITTED → SUBMITTED → APPROVED |
PARTIALLY_APPROVED | DENIED`, then `CREDIT_RECEIVED` or `WRITTEN_OFF`; `DEALER`-type providers
(self-funded) skip reimbursement entirely via `NOT_APPLICABLE`. Every transition emits an outbox
event that pos-accounting consumes to match expected vendor credits — warranty never writes to
accounting directly.

A **part return** (`PartReturn`, at most one per claim line — an RMA) tracks the defective part:
`PartReturnStatus` is `AWAITING_PART → ON_HOLD → SHIPPED → RECEIVED_BY_VENDOR | SCRAPPED | CLOSED`,
with a `PartReturnDisposition` of `HOLD_FOR_INSPECTION`, `RETURN_TO_VENDOR`, `SCRAP_AUTHORIZED`, or
`CUSTOMER_RETAINED`. The physical hold shelf is manual in v1; this record is the system of record.
Both lifecycles can remain open after the claim reaches `SETTLED` — they block only `CLOSED`, per
the state-machine section above.

## Endpoints and permissions
Warranty endpoints live under `/v1/warranty/...`; every endpoint requires `bearerAuth` plus a
specific permission (`WarrantyPermissions`, backed by `permissions.yaml`):

| Operation | Method / path | Permission |
|---|---|---|
| Create draft claim | `POST /v1/warranty/claims` | `warranty:claim:create` |
| Search claims | `GET /v1/warranty/claims` | `warranty:claim:view` |
| Search candidate origin lines | `GET /v1/warranty/claims/candidate-lines` | `warranty:claim:view` |
| Get claim detail | `GET /v1/warranty/claims/{id}` | `warranty:claim:view` |
| Update claim (DRAFT/INFO_NEEDED) | `PUT /v1/warranty/claims/{id}` | `warranty:claim:create` |
| Add/remove claim line | `POST`/`DELETE /v1/warranty/claims/{id}/lines...` | `warranty:claim:create` |
| Add/remove photo | `POST`/`DELETE /v1/warranty/claims/{id}/photos` | `warranty:claim:create` |
| Submit claim | `POST /v1/warranty/claims/{id}/submit` | `warranty:claim:submit` |
| Re-run eligibility | `POST /v1/warranty/claims/{id}/eligibility` | `warranty:claim:view` |
| Decide claim (approve/deny/request-info/appeal) | `POST /v1/warranty/claims/{id}/decision` | `warranty:claim:decide` |
| Cancel claim | `POST /v1/warranty/claims/{id}/cancel` | `warranty:claim:cancel` |
| Close claim | `POST /v1/warranty/claims/{id}/close` | `warranty:claim:close` |
| Add note | `POST /v1/warranty/claims/{id}/notes` | `warranty:claim:create` |
| Create settlement | `POST /v1/warranty/claims/{id}/settlements` | `warranty:claim:settle` |
| Settlement reconciliation | `GET /v1/warranty/settlements/reconciliation` | `warranty:claim:settle` |
| Submit / update reimbursement | `POST /v1/warranty/claims/{id}/reimbursement/submit`, `PUT /v1/warranty/claims/{id}/reimbursement` | `warranty:reimbursement:manage` |
| List reimbursements | `GET /v1/warranty/reimbursements` | `warranty:reimbursement:view` |
| Create/update part return | `POST /v1/warranty/claims/{id}/part-returns`, `PUT /v1/warranty/part-returns/{id}` | `warranty:part-return:manage` |
| List part returns | `GET /v1/warranty/part-returns` | `warranty:part-return:view` |
| Providers (list/get) | `GET /v1/warranty/providers`, `GET /v1/warranty/providers/{id}` | `warranty:provider:view` |
| Providers (create/update) | `POST`/`PUT /v1/warranty/providers...` | `warranty:provider:manage` |
| Policies (list/get/applicable) | `GET /v1/warranty/policies...` | `warranty:policy:view` |
| Policies (create/update) | `POST`/`PUT /v1/warranty/policies...` | `warranty:policy:manage` |
| Registrations (list/get) | `GET /v1/warranty/registrations...` | `warranty:registration:view` |
| Registrations (create/update) | `POST`/`PUT /v1/warranty/registrations...` | `warranty:registration:manage` |

## Not modeled here
Marketing pricing/upsell terms for extended-plan sales, a customer-facing self-service claim portal,
and any automatic (non-staff) settlement decision are **not** implemented in `pos-warranty`. The
assistant must not invent a self-service claim flow or an automatic settlement trigger; it should
say the platform does not model these and offer the verified claim lifecycle and settlement paths
above.

## Verified facts (pos-warranty)
- **Claim code format** is `WC-{yyyy}-{seq}`, zero-padded to 6 digits, resetting each calendar year, allocated atomically via a locked per-year sequence row.
_Verified: `pos-warranty` `ClaimCodeServiceImpl.nextClaimCode` (`String.format("WC-%d-%06d", year, allocated)`), `ClaimCodeSequence`._
- **Claim status machine** is `DRAFT, SUBMITTED, IN_REVIEW, INFO_NEEDED, APPROVED, DENIED, SETTLED, CLOSED, CANCELLED`; a claim is editable only in `DRAFT`/`INFO_NEEDED`.
_Verified: `pos-warranty` `ClaimStatus`, `ClaimStateMachine.LEGAL_TRANSITIONS`, `ClaimStateMachine.EDITABLE`, `ClaimStateMachine.assertEditable`._
- **Settle-customer-first invariant:** vendor reimbursement and part-return child lifecycles never block `APPROVED→SETTLED`; they block only `SETTLED→CLOSED`, requiring every reimbursement terminal (`CREDIT_RECEIVED, WRITTEN_OFF, NOT_APPLICABLE, DENIED`) and every part return terminal (`RECEIVED_BY_VENDOR, SCRAPPED, CLOSED`).
_Verified: `pos-warranty` `ClaimServiceImpl.enforceChildrenTerminal`, `ClaimServiceImpl.TERMINAL_REIMBURSEMENT`, `ClaimServiceImpl.TERMINAL_PART_RETURN`, `ClaimStateMachine` class javadoc._
- **Eligibility is a suggestion, not a decision:** `EligibilityServiceImpl.evaluate` matches policies most-specific-first (`PRODUCT_LIST > MANUFACTURER > CATEGORY > ALL`) and persists a suggested outcome; an `amountApproved` differing from the computed amount, a `DENY`, or an `APPEAL` requires an `overrideReason`/`reason` audited as a claim note.
_Verified: `pos-warranty` `EligibilityServiceImpl.evaluate`, `EligibilityServiceImpl.specificityRank`, `ClaimServiceImpl.decide` (lineDecisions/overrideReason handling), `ClaimDecisionRequest`._
- **Settlement types** are `REPLACEMENT_WORKORDER, INVOICE_CREDIT, REFUND, PRORATED_CREDIT, GOODWILL, NO_ACTION`; `REPLACEMENT_WORKORDER` only links an existing pos-workorder id (no write), credit/refund types call pos-invoice and fail loudly on error.
_Verified: `pos-warranty` `SettlementType`, `SettlementServiceImpl.create`, `SettlementServiceImpl.linkReplacementWorkorder`, `SettlementServiceImpl.executeInvoiceAdjustment`, `SettlementServiceImpl.executeRefund`._
- **The `externalReference` pos-invoice dedupes on is the settlement's own UUID, not the claim code string**; the claim code appears only in the adjustment's human-readable reason text and in the `warranty.claim.settled` event.
_Verified: `pos-warranty` `SettlementServiceImpl.executeInvoiceAdjustment` (`settlement.getId().toString()` passed as `externalReference`), `SettlementReconciliationServiceImpl` (matches on `settlement.getId()`)._
- **Reimbursement and part-return lifecycles**: `ReimbursementStatus` is `NOT_SUBMITTED, SUBMITTED, APPROVED, PARTIALLY_APPROVED, DENIED, CREDIT_RECEIVED, WRITTEN_OFF, NOT_APPLICABLE` (one per claim, `DEALER` providers are `NOT_APPLICABLE`); `PartReturnStatus` is `AWAITING_PART, ON_HOLD, SHIPPED, RECEIVED_BY_VENDOR, SCRAPPED, CLOSED` (one per claim line).
_Verified: `pos-warranty` `ReimbursementStatus`, `VendorReimbursement` (unique `claim_id`), `PartReturnStatus`, `PartReturn` (unique `claim_line_id`)._
- **Warranty permissions** are `warranty:{provider,policy,registration}:{view,manage}`, `warranty:claim:{view,create,submit,decide,settle,cancel,close}`, and `warranty:{reimbursement,part-return}:{view,manage}`; all endpoints live under `/v1/warranty/...`.
_Verified: `pos-warranty` `permissions.yaml`, `WarrantyPermissions`, `ClaimController`, `SettlementController`, `ReimbursementController`, `PartReturnController`, `ProviderController`, `PolicyController`, `RegistrationController`._
- **A self-service customer claim portal and automatic (non-staff) settlement are NOT modeled** — every decision and settlement action requires an authenticated staff permission; there is no unauthenticated or customer-facing claim endpoint.
_Verified: `pos-warranty` source (every `ClaimController`/`SettlementController` endpoint is `@PreAuthorize`-gated; no anonymous/customer endpoint exists)._
