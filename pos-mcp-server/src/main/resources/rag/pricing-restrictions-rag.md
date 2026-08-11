---
rag_id: pricing.restrictions
rag_scope: pricing
required_permissions:
  - AUTHENTICATED
---

## Purpose

RAG id: pricing.restrictions
RAG scope: pricing
Required permissions: AUTHENTICATED
Audience: internal staff.

This document describes implemented restriction rules, evaluation outcomes, and override issuance
behavior in pos-price. Read/evaluate endpoints are currently authenticated-only; write/override
operations require explicit restriction permissions.

## Endpoints, Permissions, and Events

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create rule | POST /v1/price/restrictions/rules | pricing:restriction:manage | PRICE_RESTRICTION_RULE_CREATE |
| Get rule | GET /v1/price/restrictions/rules/{ruleId} | isAuthenticated | none |
| List rules | GET /v1/price/restrictions/rules | isAuthenticated | none |
| Deactivate rule | DELETE /v1/price/restrictions/rules/{ruleId} | pricing:restriction:manage | PRICE_RESTRICTION_RULE_DEACTIVATE |
| Evaluate restrictions | POST /v1/price/restrictions:evaluate | isAuthenticated | PRICE_RESTRICTIONS_EVALUATE |
| Issue override | POST /v1/price/restrictions:override | pricing:restriction:override | PRICE_RESTRICTIONS_OVERRIDE |

## Restriction Decision Behavior

Per evaluated item, service behavior is:

- No matching rules: ALLOW.
- Any matching non-overrideable rule: BLOCK.
- Matching rules and all overrideable: ALLOW_WITH_OVERRIDE.

Timeout/exception handling:

- Commit contexts CHECKOUT, INVOICE_FINALIZE, COMMIT_SALE throw RestrictionServiceUnavailableException.
- Non-commit contexts return RESTRICTION_UNKNOWN.

## Override Issuance Behavior

Override issuance persists a RestrictionOverrideAudit row with:

- status ISSUED
- issuedAt now(clock)
- expiresAt = issuedAt + 24 hours
- policyVersion copied from rule
- actor from SecurityContextHelper current user

Current service implementation does not transition override status to APPROVED or DENIED.

## Enum and Token Catalog

RestrictionDecision:

- ALLOW
- BLOCK
- ALLOW_WITH_OVERRIDE
- RESTRICTION_UNKNOWN

EvaluationContext:

- BROWSE
- QUOTE
- CHECKOUT
- INVOICE_FINALIZE
- COMMIT_SALE

LocationTag:

- ALL_LOCATIONS
- RETAIL_STORE
- WAREHOUSE
- MOBILE_SERVICE
- FRANCHISE
- TEST_LOCATION

ServiceTag:

- POS_SALE
- WORKORDER
- ESTIMATE
- INVOICE
- DELIVERY

OverrideStatus:

- ISSUED
- APPROVED
- DENIED

## Declared Catalog Mismatches

- Singular permission codes are enforced in controllers: pricing:restriction:*.
- Plural permission codes are declared in permissions.yaml but not used in these controllers:
  pricing:restrictions:view and pricing:restrictions:edit.

## Verified Facts

- _Verified: pos-price RestrictionRuleController and PriceRestrictionsController endpoint mappings, security guards, and EmitEvent ids._
- _Verified: pos-price RestrictionEvaluationServiceImpl decision tree for ALLOW, BLOCK, ALLOW_WITH_OVERRIDE, and context-based unavailable behavior._
- _Verified: pos-price RestrictionOverrideServiceImpl issues overrides with 24-hour expiry and persists RestrictionOverrideAudit in ISSUED status._
- _Verified: pos-price enums RestrictionDecision, EvaluationContext, LocationTag, ServiceTag, and OverrideStatus._
