# Tax Guide

## Purpose
RAG id: `tax.guide`  
RAG scope: `tax`  
Required permissions: `tax:mode:view` (pos-tax read permission).  
Audience: internal staff and accounting.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

Tax visibility is gated by `tax:mode:view` (read) in `pos-tax`; tax calculation uses `tax:calculate`. This document explains how the system interprets tax, not legal tax advice.

_Verified against `pos-tax/src/main/resources/permissions.yaml`: the service exposes exactly `tax:mode:view` and `tax:calculate`._

## Core concepts
Tax in a service ERP is normally determined by the taxable entity, jurisdiction, customer/account status, product or service category, date, exemption status, and invoice/estimate context. The assistant should not provide legal tax advice. It should explain system interpretation, identify missing data, and distinguish tax estimate from finalized tax.

Tax-related questions often occur during estimate creation, invoice generation, credit/rebill, warranty/claim adjustment, and accounting reconciliation. Where the source does not verify the calculation rule, the assistant should say what inputs are needed and mark the rule for verification.

## Staff questions and answer patterns
| Question | Interpretation |
|---|---|
| "Why was tax charged on this line?" | Identify item category, service/parts distinction, location/jurisdiction, exemption context, and calculation source if available. |
| "Why is this customer tax exempt?" | Look for account/customer exemption evidence; do not infer from customer type alone. |
| "Why did tax change from estimate to invoice?" | Compare approved estimate, change requests, final invoice lines, location/date, and taxable base. |
| "Can I remove tax?" | Treat as an approval/governance issue; require verified permission and audit context. |
| "What tax should apply?" | Ask for jurisdiction, customer/account, line items, date, and exemption document status. |

## Estimate, workorder, and invoice timing
Tax shown on an estimate should be treated as pre-invoice calculation until the invoice is generated or finalized. If a workorder adds parts or labor through an approved change request, the taxable base may change. The assistant should avoid saying tax is final until invoice finalization source confirms it. The verified invoice permissions are coarse (`invoice:manage`, `invoice:finalize`) and the bundle explicitly notes no `invoice:read` sample.

## Accounting hand-off
Tax collected or accrued may need a liability account, but the bundle does not provide tax-account mapping rules. Use the accounting RAG principles: journal entries must balance, posted entries should be immutable, and corrections require reversing or compensating entries. Do not invent tax liability accounts or jurisdiction remittance rules.

## Error and exception patterns
Common issues include missing customer exemption, wrong location, wrong vehicle/service address, product/service taxable-category mismatch, estimate/invoice date difference, manual line adjustment, missing approval for tax override, and jurisdiction rule not loaded.

## Verified ownership (pos-tax)
`pos-tax` is a thin service that **delegates tax calculation to an external tax provider** (`ExternalTaxServiceClient`, Resilience4j retry) with a deterministic `TestModeTaxCalculator` fallback (`TaxConfiguration`/`TaxProperties`/`TaxController`). It does **not** model jurisdictions, exemption documents, or taxable-category mappings internally — those live in the external provider. Permissions: `tax:calculate`, `tax:mode:view`. Tax→accounting posting is NOT owned by pos-tax: tax is presented on the invoice (`pos-invoice`) and posted to the GL by `pos-accounting`. So jurisdiction/exemption/category rules are UNVERIFIABLE from this repo (external), and there is no internal tax-override permission.

_Verified: `pos-tax` `TaxCalculationServiceImpl`, `ExternalTaxServiceClient`, `TestModeTaxCalculator`, `permissions.yaml` (tax:calculate, tax:mode:view)._
