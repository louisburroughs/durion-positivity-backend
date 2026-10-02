---
rag_id: accounting.invoicing-payments
rag_scope: accounting
required_permissions:
  - invoice:invoice:view
  - accounting:payment:apply
---

## Purpose

RAG id: `accounting.invoicing-payments`
RAG scope: `accounting`
Required permissions: `invoice:invoice:view`, `accounting:payment:apply`
Audience: internal staff.
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This document grounds invoice and payment questions for the natural-language assistant: how a
customer invoice is created, finalized, posted and reverted in `pos-invoice`; how card payments,
voids, refunds, deposits and receipts work there; and how `pos-accounting` turns a settled payment
into a receivable payment, applies it to invoices, derives the paid / partially paid / unpaid state
and ages open balances. It describes implemented behaviour only. Where a request names something
the platform does not do (sending an invoice by email, automatic payment application, a "past due"
filter on invoice search), this document says so, and the assistant must not invent it.

Posting semantics belong to the accounting domain (`accounting.journal-entries`,
`accounting.de-bookkeeping`); this document only says which journal entries the invoice and payment
facts cause.

## Invoice status versus payment status

An invoice carries two different states, held by two services. Never mix them.

| Question | Service and source | Values |
| --- | --- | --- |
| Where is the invoice in its lifecycle? | `pos-invoice` `InvoiceStatus` | `DRAFT`, `FINALIZED`, `POSTED`, `ERROR`, `CANCELLED` |
| How much of it has been paid? | `pos-accounting` `PaymentStatus` (`GET /v1/accounting/invoice/{invoiceId}/status`) | `PAID`, `PARTIALLY_PAID`, `UNPAID`, `FAILED`, `CHARGEBACK` |
| What is its receivable state? | `pos-accounting` `InvoiceStatus`, derived by `InvoiceBalanceCalculator` | `OPEN`, `PARTIALLY_PAID`, `PAID_IN_FULL` |

`POSTED` means the revenue journal entry is in the ledger; it does **not** mean paid. "Unpaid" is
an accounting answer: an invoice with a positive balance due. The billing business-rule guide
(`durion` repository, `domains/billing/.business-rules/`) still names a target vocabulary
(`ISSUED`, `PAID`, `VOID`) and a `billing:*` permission set; neither is implemented. Use the values
and codes in this document.

## Invoice identifiers and number formats

- `invoiceId`: UUIDv7 primary key; every `pos-invoice` endpoint addresses an invoice by it.
- Invoice number: `INV-<epochMillis>-<first 8 hex chars of the invoice UUID>`, assigned once when
  the draft is created (the `glossary.identifiers` document states the same format). DTO examples
  such as `INV-2026-1001` are illustrative only. To go from an invoice number to an `invoiceId`,
  use invoice search (`q` matches the number).
- Receipt reference: `RCP-<invoice number>-<yyyyMMdd'T'HHmmss'Z'>-<3-digit sequence per invoice>`.

## Invoice lifecycle states and transitions

The `pos-invoice` `InvoiceStatus` lifecycle:

```text
DRAFT ──finalize──▶ FINALIZED ──accounting.invoice.gl-posted──▶ POSTED (immutable)
  ▲                     │
  └──revert, ≤ 24 h─────┘
DRAFT ──cancel, no money moved──▶ CANCELLED (terminal)
```

| Transition | Trigger | Permission |
| --- | --- | --- |
| `DRAFT` → `FINALIZED` | `POST /v1/invoices/{invoiceId}/finalize` | `invoice:finalize` |
| `FINALIZED` → `POSTED` | `pos-accounting` answers `accounting.invoice.gl-posted` | none (event) |
| `FINALIZED` → `DRAFT` | `POST /v1/invoices/{invoiceId}/revert`, within 24 hours of `finalizedAt` | `invoice:finalize` |
| `DRAFT` → `CANCELLED` | `POST /v1/invoices/{invoiceId}/cancel` | `invoice:manage` |

- A `POSTED` invoice cannot be reverted or edited.
- `ERROR` exists in the enum; no service path in `pos-invoice` sets it today.
- Revenue analytics count only `FINALIZED` and `POSTED` invoices.

## Creating an invoice

A `pos-invoice` draft invoice is created from a completed workorder or from a sales order:

- `POST /v1/workorders/{workorderId}/generate-invoice` (`workorder:workorder:generate_invoice`)
  queues generation for a `COMPLETED` workorder (202 `PENDING`); `pos-invoice` creates the draft
  from the `invoice.generation-requested` command and the workorder later shows the invoice id.
- `POST /v1/invoices` (`invoice:manage`) creates the draft directly; idempotent on `workorderId`.
- `POST /v1/invoices/from-order` (`invoice:manage`) fronts a sales order at checkout, idempotent on
  `orderId`; a workorder-linked order reuses the workorder's existing invoice.
- One invoice per workorder: `GET /v1/invoices/by-workorder/{workorderId}` (`invoice:invoice:view`)
  returns it, 404 when none is linked yet.

## Adjusting, finalizing and posting an invoice

- **Adjustment.** `POST /v1/invoices/{invoiceId}/adjustments` (`invoice:manage`) applies a
  `DISCOUNT`, `FEE`, `CORRECTION` or `WARRANTY` adjustment to a `DRAFT` invoice only. An adjustment
  that would drive the total negative is refused with 422 `EXCESSIVE_ADJUSTMENT`; a credit memo in
  `pos-accounting` is the instrument instead.
- **Finalization.** `POST /v1/invoices/{invoiceId}/finalize` (`invoice:finalize`) freezes tax,
  totals, payment terms and the due date. For an invoice total above 500.00, a caller without
  `invoice:finalize:override` (seeded to the admin and manager roles) must supply a manager-approval
  elevation token from `POST /v1/billing/auth/elevate`; a missing token is 403
  `MANAGER_APPROVAL_REQUIRED`, an invalid or expired one 403 `MANAGER_APPROVAL_INVALID`.
- **Posting.** Finalization emits `invoice.invoice.updated` (status `FINALIZED`). `pos-accounting`
  posts the revenue entry (Dr Accounts Receivable, Cr Service Revenue, Cr Sales Tax Payable, dated
  at `finalizedAt`) and answers `accounting.invoice.gl-posted`; `pos-invoice` then moves the
  invoice to `POSTED` and records the journal entry id as `glEntryId`. `pos-invoice` never writes
  to the ledger itself.

## Reverting or cancelling an invoice

- **Revert.** `POST /v1/invoices/{invoiceId}/revert` (`invoice:finalize`) returns a `FINALIZED`
  invoice to `DRAFT` within 24 hours of `finalizedAt`. The request needs a reason and a
  `managerApprovalCode`, which must be a valid elevation token unless the caller holds
  `invoice:finalize:override`. A `POSTED` invoice cannot be reverted (409). `pos-accounting`
  reverses the invoice's revenue entry when it sees the reverted fact.
- **Cancel.** `POST /v1/invoices/{invoiceId}/cancel` (`invoice:manage`) terminally cancels a
  `DRAFT` invoice that has no `AUTHORIZED` or `CAPTURED` payment (the order-void path).
- **After the revert window.** A finalized invoice older than 24 hours, or any `POSTED` invoice, is
  corrected by a credit memo in `pos-accounting`, not by editing the invoice.

## Finding and searching invoices

| Need | Endpoint | Permission |
| --- | --- | --- |
| One invoice by id (lines, adjustments, totals) | `GET /v1/invoices/{invoiceId}` | `invoice:invoice:view` |
| Search by number, customer name or workorder number | `GET /v1/invoices/search?q=&status=&issuedFrom=&issuedTo=&customerId=` | `invoice:invoice:view` |
| Invoice lines of one customer | `GET /v1/invoices/items/search?partyId=` | `invoice:invoice:view` |
| Revenue by customer for a window | `GET /v1/invoices/analytics/revenue-by-customer` | `invoice:analytics:view` |
| Average days from workorder creation to invoice | `GET /v1/invoices/analytics/invoicing-lag` | `invoice:analytics:view` |

Invoice search returns 25 rows per page by default (cap 50), newest first; `status` must be an
exact `InvoiceStatus`; `issuedFrom`/`issuedTo` bound `finalizedAt`, so drafts drop out when a date
bound is set. **Search has no paid, unpaid or past-due filter.** For "show unpaid invoices for
Smith": find the customer, list their `FINALIZED`/`POSTED` invoices, then read each one's payment
status from `pos-accounting` (`GET /v1/accounting/invoice/{invoiceId}/status`,
`accounting:ap:view`), or read the customer's row of the aged receivables report
(`GET /v1/accounting/reports/financial/aged-receivables`). Say which source the answer used.

## Card payment endpoints and permissions (pos-invoice)

A `pos-invoice` payment is a **payment intent** raised against one invoice through the payment
gateway port.

| Operation | Endpoint | Permission |
| --- | --- | --- |
| Take card tender | `POST /v1/invoices/{invoiceId}/payments` | unregistered authority (note after this table) |
| Capture an authorized hold | `POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture` | unregistered authority (note after this table) |
| List an invoice's payments | `GET /v1/invoices/{invoiceId}/payments` | `invoice:invoice:view` |
| One payment's detail | `GET /v1/invoices/{invoiceId}/payments/{paymentId}` | `invoice:invoice:view` |
| Void an authorized hold | `POST /v1/invoices/{invoiceId}/payments/{paymentId}/void` | `invoice:payment:void` |
| Refund a captured payment | `POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds` | `invoice:payment:refund` |
| Refunds of an invoice | `GET /v1/invoices/{invoiceId}/refunds` | `invoice:invoice:view` |
| Manual refund, no captured payment | `POST /v1/invoices/{invoiceId}/refunds`, `POST /v1/refunds` | `invoice:refund:issue_manual` |

Payment initiation and capture check the authority names `PROCESS_PAYMENT`,
`OVERRIDE_PAYMENT_LIMIT` (amount above 500.00), `SELECT_PAYMENT_FLOW` (`AUTH_ONLY`) and
`MANUAL_CAPTURE` inside the service. None of them is in the `pos-invoice` registered permission set
(`permissions.yaml`), so no role can be granted them through role administration. Report a 403 on
payment initiation or capture as a configuration gap rather than naming a permission to request.

## Card payment flows, idempotency and declines (pos-invoice)

- **Flows.** `SALE_CAPTURE` charges at once (intent `CAPTURED`); `AUTH_ONLY` places a hold
  (`AUTHORIZED`) that a later capture settles, in full or in part; the uncaptured remainder is
  voided. `PaymentIntentStatus`: `PENDING`, `AUTHORIZED`, `CAPTURED`, `CAPTURE_FAILED`, `VOIDED`,
  `EXPIRED`.
- **Idempotency.** Every payment initiation carries an `idempotencyKey`; a replay with the same
  payload returns the existing intent, a different payload is 409 `PAYMENT_IDEMPOTENCY_CONFLICT`.
- **Declines.** A gateway decline marks the intent `CAPTURE_FAILED` and answers 422
  `PAYMENT_DECLINED`; an ambiguous gateway answer is resolved by one status inquiry first. There is
  no automatic retry of a declined payment: a new attempt is a new initiation.
- **Gateway binding.** The only `PaymentGatewayPort` implementation in this codebase is a
  placeholder that refuses every call until an environment supplies a real adapter. If card
  payments fail everywhere, that is the likely reason; do not promise a processor.
- **Partial payments.** The service does not compare a payment amount with the invoice total, so a
  payment smaller than the total is accepted, and the billing rules (BILL-DEC-012) say partial
  payments are supported. Whether a given customer may pay in part is not configured anywhere in
  code; ask the user when it matters.
- **Facts.** A capture publishes `payment.payment.settled` on `payment.events.v1`.

## Payment voids and refunds (pos-invoice)

- **Windows.** Both windows are measured from when the payment intent was created (its
  authorization, or its charge for `SALE_CAPTURE`), not from a later capture.
- **Void.** Voiding needs an `AUTHORIZED` payment intent. More than 24 hours after the intent was
  created it also needs `invoice:payment:override`; otherwise 422 `PAYMENT_WINDOW_EXPIRED`.
- **Refund.** Refunding needs a `CAPTURED` payment intent and cannot exceed what is still
  refundable (422 `INSUFFICIENT_REFUNDABLE_AMOUNT`). More than 180 days after the intent was created it also needs
  `invoice:payment:override`; otherwise 422 `PAYMENT_WINDOW_EXPIRED`.
- **Refund reasons** are the `RefundReason` values: `CUSTOMER_RETURN`, `SERVICE_ERROR`,
  `OVERCHARGE`, `DAMAGED_GOODS`, `GOODWILL`, `CHARGEBACK_AVOIDANCE`, `FRAUD_PREVENTION`,
  `MANAGER_DISCRETION`, `OTHER`.
- **Manual refund.** A refund with no captured payment behind it (money returned out of band) is
  recorded with `invoice:refund:issue_manual`.
- **Facts.** Voids and refunds publish `payment.payment.reversed` on `payment.events.v1`.

## Recording a payment against invoices

A request like "record a payment on INV-…" has two halves, and the assistant should say which one
it means:

1. **Taking the money** is the `pos-invoice` card payment flow (or a manual refund record for money
   returned out of band). There is no endpoint that records a cash or cheque receipt as such.
2. **Applying a receivable payment to invoices** happens in `pos-accounting`. A
   `payment.payment.settled` fact with a customer creates a `ReceivablePayment` whose whole amount
   is unapplied (`AVAILABLE`); a settled payment without a customer (anonymous counter sale) is
   skipped. Nothing applies it automatically: the only path is
   `POST /v1/accounting/payments/{paymentId}/applications` (`accounting:payment:apply`).

## Payment application rules (pos-accounting)

The `pos-accounting` payment-application command
(`POST /v1/accounting/payments/{paymentId}/applications`, `accounting:payment:apply`) carries an
`applicationRequestId` (idempotency key) and a list of `{invoiceId, amountToApply}`. Rules, as the
code enforces them:

- Each invoice must be known to accounting (404 otherwise), be `FINALIZED` or `POSTED`, and still
  have a positive balance due (409 otherwise).
- The sum requested may not exceed the payment's unapplied amount (400); a payment in a currency
  other than the ledger's is refused (422 `CURRENCY_NOT_SUPPORTED`).
- Each line is capped at that invoice's balance; the excess becomes a **customer credit**.
- All lines apply in one transaction, or none do.
- **Allocation order.** `allocationStrategy` is optional. `CALLER_ORDER` (the default when it is
  omitted) keeps the order sent; `OLDEST_FIRST` orders the lines by due date ascending, falling
  back to the finalization time, then by invoice id. Oldest-first is therefore **not** automatic:
  it applies only when the request asks for `OLDEST_FIRST`, and it orders the invoices the caller
  listed rather than choosing invoices for the customer. Oldest-first (FIFO) is a common
  cash-application convention when a customer sends no remittance instruction (see Sources [5]);
  on this platform someone must still choose it.
- **Journal entries.** An application posts Dr Undeposited Funds, Cr Accounts Receivable; the
  overpayment credit posts Dr Undeposited Funds, Cr Customer Credit Liability.
- **Reversal.** An application is undone with
  `POST /v1/accounting/payment-applications/{applicationId}/reverse` (`accounting:payment:reverse`);
  the list is `GET /v1/accounting/payment-applications` (`accounting:analytics:view`).

## Invoice balance due and paid status (pos-accounting)

**Balance due** = invoice total − (applied − reversed) − posted credit memos − applied customer
credits − applied deposit credits.

From the balance due, `pos-accounting` derives `PAID_IN_FULL` (balance ≤ 0), `PARTIALLY_PAID`
(between zero and the total) or `OPEN`, and the payment-status endpoint
(`GET /v1/accounting/invoice/{invoiceId}/status`, `accounting:ap:view`) answers `PAID`,
`PARTIALLY_PAID` or `UNPAID`. `FAILED` and `CHARGEBACK` are written by accounting's payment-outcome
processing.

How a chargeback is worked, and who reconciles processor settlements (`pos-accounting` consumes the
settlement facts; the default settlement feed binding is a placeholder), is not defined for staff
in code: ask before describing either.

## Deposit credits

A deposit is money taken before the work or sale is billed. In accounting terms it is not yet
revenue: the business owes the customer the goods or service, so the amount is a liability (a
contract liability, often called unearned revenue or customer deposits) until it is earned (see
Sources [3], [4]).

- **Taking a deposit.** A sales order checked out with a positive `depositAmount` produces a
  zero-tax **deposit-take invoice** through `POST /v1/invoices/from-order`, and registers a
  **deposit credit** against its source (`DepositSourceType`: `ESTIMATE`, `WORKORDER`, `ORDER`).
  `POST /v1/invoices/deposits` (`invoice:manage`) registers one directly; both are idempotent on
  the taking `orderId`.
- **Statuses.** `DepositCreditStatus`: `AVAILABLE`, `PARTIALLY_APPLIED`, `FULLY_APPLIED`,
  `REFUNDED`. Read with `GET /v1/invoices/deposits/{depositCreditId}` or
  `GET /v1/invoices/deposits?sourceType=&sourceId=` (`invoice:invoice:view`).
- **Using a deposit on a workorder.** When a settlement invoice is created through
  `POST /v1/invoices/from-order` for an order that fronts a workorder, the workorder's `WORKORDER`
  deposit credits are drawn down oldest credit first, each at most once per invoice, up to the
  invoice total. Each draw-down publishes `payment.deposit-credit.applied`, which accounting
  subtracts from the balance due. The other invoice paths (`POST /v1/invoices`, generate-invoice,
  and a from-order call that reuses an existing workorder invoice) do not draw deposits down.
- **Refunding a deposit.** `POST /v1/invoices/deposits/{depositCreditId}/refund` (`invoice:manage`)
  zeroes the remaining balance and marks it `REFUNDED`; it records the return only, the cash
  disbursement happens outside this call.
- **Reporting.** Deposit-take invoices are excluded from revenue by customer and from the revenue
  journal entry; `pos-accounting` has no separate deposit-liability posting in this codebase.

## Payment receipts

| Operation | Endpoint | Permission |
| --- | --- | --- |
| Generate a receipt for a payment | `POST /v1/invoices/{invoiceId}/receipts` | `invoice:receipt:generate` |
| Read a receipt | `GET /v1/invoices/{invoiceId}/receipts/{receiptId}` | `invoice:invoice:view` |
| Record a print outcome | `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/print` | `invoice:receipt:generate` |
| Record an email outcome | `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/email` | `invoice:receipt:generate` |
| Reprint | `POST /v1/invoices/{invoiceId}/receipts/{receiptId}/reprint` | signed-in; beyond 5 reprints `invoice:receipt:reprint_override` |

A `pos-invoice` receipt is created explicitly for one payment intent (status `GENERATED`). The
print and email endpoints **record** a delivery outcome (`SUCCESS` or `FAILED`, plus the address
for email); they do not print or send anything themselves. The fifth reprint is the last without
the override; a sixth is 409 `REPRINT_LIMIT_EXCEEDED`.

## Sending or emailing an invoice

**There is no endpoint that emails or sends an invoice.** `BillingRules.invoiceDeliveryMethod`
(`EMAIL`, `PORTAL`, `MAIL`) stores a customer's preference only. Invoice documents are reached as
artifacts: `GET /v1/invoices/{invoiceId}/artifacts` and a short-lived download token
(`POST /v1/invoices/{invoiceId}/artifacts/{artifactRefId}/download-token`). The receipt print and
email endpoints record an outcome only; they do not deliver an invoice either.

## Payment terms and invoice due dates

- At finalization a `pos-invoice` invoice gets a frozen due date. A bill-to party of type
  `COMMERCIAL` uses its billing rules' `PaymentTerms` (`DUE_ON_RECEIPT`, `NET_10`, `NET_15`,
  `NET_30`, `NET_45`, `NET_60`), else the default `NET_30`; any other party, or one that cannot be
  resolved, is `DUE_ON_RECEIPT`.
- Net days are added to the finalization date in the location's time zone.
- Billing rules are read and written at `/v1/billing/rules/{partyId}` (`invoice:billing-rules`).

## Past-due invoices and aged receivables

- **Past due** means an open invoice balance whose due date has passed. Days past due are counted
  from the due date to the as-of date, the usual definition (see Sources [1], [2]); an invoice
  without a due date ages from its invoice date.
- **Aged receivables.** `GET /v1/accounting/reports/financial/aged-receivables?asOfDate=`
  (`reporting:view:financial-statements`) lists, per customer id, open balances of `FINALIZED` and
  `POSTED` invoices in buckets `current` (not yet due, or up to 30 days past due), `days31To60`,
  `days61To90` and `days90Plus`, with totals. The first bucket mixes not-yet-due and up to 30 days
  late, unlike the common layout with a separate "current" column (see Sources [1]). Balances are
  today's, even for a back-dated `asOfDate`, and `customerName` is empty in this version.
- For "which invoices are past due?" answer from aged receivables (amounts per customer) and, per
  invoice, the `pos-accounting` payment status plus its due date; never from `InvoiceStatus` alone.

## How invoices relate to other records

- **Workorder:** one invoice per workorder (`workorderId`, `workorderNumber` on the invoice);
  generate-invoice accepts only a `COMPLETED` workorder.
- **Sales order:** `orderId`; checkout of a workorder-linked order tenders the workorder's invoice.
- **Customer:** `partyId` (null for anonymous counter sales, which then never become receivables).
- **Journal entries:** the revenue entry (`glEntryId` once `POSTED`), payment-application entries,
  customer-credit entries and credit memos, all posted by `pos-accounting`.
- **Warranty:** a warranty settlement credits a draft invoice as a `WARRANTY` adjustment carrying
  the settlement id as `externalReference`.

## Invoice and payment questions to ask the user

- Partial payment: whether this customer or job may pay in part (not configured in code).
- Declined payment: whether to try a new payment after a decline, and with which tender.
- Reconciliation and chargebacks: who owns them at this shop.
- Changes after finalization: only by reverting within 24 hours (then adjusting the draft) or by a
  credit memo in accounting; confirm which the user intends.

## Sources

Platform sources in `durion-positivity-backend` (repository-relative):

- `pos-invoice/README.md`, `pos-invoice/openapi.yaml`,
  `pos-invoice/src/main/resources/permissions.yaml`
- `pos-invoice/src/main/java/com/positivity/invoice/internal/enums/`: `InvoiceStatus`,
  `PaymentIntentStatus`, `PaymentFlow`, `PaymentTerms`, `DepositCreditStatus`, `DepositSourceType`,
  `InvoiceAdjustmentType`, `InvoiceDeliveryMethod`, `ReceiptStatus`, `ReceiptDeliveryStatus`,
  `RefundReason`
- `pos-invoice/src/main/java/com/positivity/invoice/internal/controller/`: `InvoiceController`,
  `InvoiceSearchController`, `InvoiceAnalyticsController`, `ElevationController`,
  `PaymentController`, `PaymentReversalController`, `StandaloneRefundController`,
  `DepositCreditController`, `ReceiptController`, `InvoiceArtifactController`,
  `BillingRulesController`
- `pos-invoice/src/main/java/com/positivity/invoice/internal/service/`: `InvoiceServiceImpl`,
  `InvoiceFinalizationServiceImpl`, `PaymentServiceImpl`, `PaymentReversalServiceImpl`,
  `ReceiptServiceImpl`, `DepositCreditServiceImpl`, `OrderInvoiceServiceImpl`,
  `InvoiceDueDateService`, `InvoiceAnalyticsServiceImpl`
- `pos-invoice/src/main/java/com/positivity/invoice/internal/config/PaymentGatewayConfiguration.java`,
  `pos-invoice/src/main/java/com/positivity/invoice/internal/payment/UnavailablePaymentGatewayAdapter.java`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/`:
  `PaymentApplicationServiceImpl`, `InvoiceBalanceCalculator`, `InvoicePaymentStatusServiceImpl`,
  `FinancialReportingServiceImpl`, `SettlementEventsListener`, `InvoiceRevenuePostingService`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/enums/`: `AllocationStrategy`,
  `InvoiceStatus`, `PaymentStatus`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/controller/`:
  `PaymentApplicationController`, `InvoicePaymentController`, `FinancialReportingController`
- `pos-accounting/src/main/java/com/positivity/accounting/internal/handler/`:
  `PaymentApplicationGLPostingEventHandler`, `CustomerCreditIssuanceGLPostingEventHandler`
- `pos-accounting/src/main/resources/permissions.yaml`
- `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderController.java`
  (`generateWorkorderInvoice`), `pos-workorder/src/main/resources/permissions.yaml`
- `pos-mcp-server/src/main/resources/rag/glossary-identifiers.md` (invoice number)

Platform sources in `durion` (repository-relative):

- `domains/billing/.business-rules/AGENT_GUIDE.md` and `DOMAIN_NOTES.md` (BILL-DEC-001,
  BILL-DEC-008, BILL-DEC-011, BILL-DEC-012)
- `domains/accounting/.business-rules/AGENT_GUIDE.md` (apply payment, AD-010)

External sources (general accounting knowledge only; paraphrased, not quoted):

1. "Accounts Receivable Aging Defined". Oracle NetSuite.
   <https://www.netsuite.com/portal/resource/articles/accounting/accounts-receivable-aging.shtml>.
   Accessed 2026-10-02.
2. "What is the meaning of aging?". AccountingCoach.
   <https://www.accountingcoach.com/blog/what-is-the-meaning-of-aging>. Accessed 2026-10-02.
3. "Would you please explain unearned income?". AccountingCoach.
   <https://www.accountingcoach.com/blog/unearned-deferred-revenue>. Accessed 2026-10-02.
4. "IFRS 15 Revenue from Contracts with Customers", Appendix A (contract liability). IFRS
   Foundation.
   <https://www.ifrs.org/content/dam/ifrs/publications/html-standards/english/2025/issued/ifrs15.html>.
   Accessed 2026-10-02.
5. "The Cash Application Checklist: How to Stop Unapplied Payments from Wrecking Your AR".
   Beancount.io. <https://beancount.io/blog/2026/04/24/cash-application-checklist-ar-process-guide>.
   Accessed 2026-10-02.
