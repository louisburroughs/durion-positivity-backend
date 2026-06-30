# Pricing Guide

## Purpose
RAG id: `pricing.guide`  
RAG scope: `pricing`  
Required permissions: `pricing:price_book:view`, `pricing:rule:view`  
Audience: internal staff.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide grounds price-book and pricing-rule questions for staff. It does not define customer-facing discount policy, margin targets, or contractual pricing that cannot be verified from source.

## Core concepts
A price book is a structured source of prices for products, services, labor, fees, or other chargeable items. A pricing rule modifies or selects a price based on context such as customer/account, location, product/SKU, service type, date, quantity, or contract. The verified read-level pricing permissions in the bundle are `pricing:price_book:view` and `pricing:rule:view`.

The assistant should explain pricing answers as a derivation: base price, applicable rule, condition, adjustment, effective date, and unresolved dependencies. It should not invent a discount, override, margin, or customer-specific contract rule.

## Staff questions and answer patterns
| Question | Interpretation |
|---|---|
| "Why is this tire priced this way?" | Identify price book, SKU, effective date, rule conditions, and any account/location modifier. |
| "Which price book applies?" | Explain the selection context and ask for missing customer, location, SKU, or date. |
| "Did the discount apply?" | Check whether a rule condition was met; do not assume a discount without rule evidence. |
| "What changed in price?" | Compare effective price-book/rule versions if version history is available. |
| "Can I override this price?" | Explain that override rules require verified permission/policy; do not infer authority from role name alone. |

## Pricing in the estimate-to-invoice flow
Pricing is usually consulted before customer approval. Estimate lines should show parts, labor, and service prices before work begins. If a change request adds work, pricing should be recalculated or reviewed before approval. Invoice generation should use the approved final work scope. If the invoice differs from the estimate, the assistant should check change requests, added lines, tax/fee treatment, and version/effective-date changes.

## Interpreting pricing answers
The assistant should use precise language:

- "Base price" means the starting price from the selected price book.
- "Rule" means a conditional adjustment or selection rule.
- "Effective date" means the date used to decide whether a price or rule applies.
- "Override" means a manual or exceptional price change and should be treated as auditable.
- "Customer/account price" means account-specific pricing only when the source verifies it.

## Error and exception patterns
Common pricing problems include missing SKU, expired price book, conflicting rules, wrong account context, wrong location, date mismatch, unit-of-measure mismatch, manual override without approval, and price differences between estimate and invoice.

> TODO(verify): pricing rule precedence, price override permissions, effective-date semantics, and whether tax/fee calculation is owned by pricing, invoice, or tax services.
