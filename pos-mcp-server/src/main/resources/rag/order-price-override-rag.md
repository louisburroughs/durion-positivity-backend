---
rag_id: order.price-override
rag_scope: order
required_permissions:
  - order:price_override:view
---

## Purpose

RAG id: order.price-override
RAG scope: order
Required permissions: order:price_override:view
Audience: internal staff.

This document describes the implemented price-override workflow in pos-order: request,
auto-approval or pending approval, explicit approve/reject, query filters, and token-level
constants used by retrieval and operators.

## Endpoints and Permissions

Base path: /v1/orders/price-overrides

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Apply override | POST /v1/orders/price-overrides | order:price_override:apply | ORDER_PRICE_OVERRIDE_APPLY |
| Approve override | POST /v1/orders/price-overrides/{overrideId}/approve | order:price_override:approve | ORDER_PRICE_OVERRIDE_APPROVE |
| Reject override | POST /v1/orders/price-overrides/{overrideId}/reject | order:price_override:reject | ORDER_PRICE_OVERRIDE_REJECT |
| Get override by id | GET /v1/orders/price-overrides/{overrideId} | order:price_override:view | none |
| Search overrides | GET /v1/orders/price-overrides | order:price_override:view | ORDER_PRICE_OVERRIDE_SEARCH |
| List pending approvals | GET /v1/orders/price-overrides/pending | order:price_override:approve | ORDER_PRICE_OVERRIDE_LIST_PENDING |

## Approval Rules

- Approval threshold by amount: 50.0
- Approval threshold by percentage: 10.0
- Approval is required when discountAmount >= 50.0 or discountPercentage > 10.0.
- Overrides are only allowed while the order is DRAFT.
- Overrides with no required approval are set to APPROVED and applied immediately.

## Status and Reason Tokens

OverrideStatus:
- PENDING_APPROVAL
- APPROVED
- REJECTED
- APPLIED
- CANCELLED

PriceOverrideReasonCode:
- CUSTOMER_LOYALTY
- PRICE_MATCH
- PROMOTIONAL_PRICING
- PRICING_ERROR_CORRECTION
- VOLUME_DISCOUNT
- GOODWILL_ADJUSTMENT
- MANAGER_DISCRETION
- OTHER

## Current Runtime State Behavior

- Apply path uses initial status PENDING_APPROVAL or APPROVED.
- Approve endpoint allows only PENDING_APPROVAL -> APPROVED.
- Reject endpoint allows only PENDING_APPROVAL -> REJECTED.
- APPLIED and CANCELLED exist in enum but are not assigned by current service transitions.

## Implementation Notes

- reviewerRole in approval records is an audit attribute sourced from caller roles.
- No tiered role-to-amount approval matrix is implemented; thresholding is flat numeric logic.

## Verified Facts

- _Verified: pos-order PriceOverrideController endpoint mappings, security scopes, PreAuthorize permissions, and EmitEvent ids._
- _Verified: pos-order PriceOverrideServiceImpl constants APPROVAL_THRESHOLD_AMOUNT=50.0 and APPROVAL_THRESHOLD_PERCENTAGE=10.0, plus requiresApproval predicate._
- _Verified: pos-order PriceOverrideServiceImpl enforces DRAFT-only guard for applying overrides and PENDING_APPROVAL-only guards for approve/reject._
- _Verified: pos-order OverrideStatus and PriceOverrideReasonCode enum token sets._
