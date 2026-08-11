---
rag_id: pricing.codes
rag_scope: pricing
required_permissions:
  - pricing:promotion:view
---

## Purpose

RAG id: pricing.codes
RAG scope: pricing
Required permissions: pricing:promotion:view
Audience: internal staff.

Token catalog for pricing-domain lexical retrieval. This document is intentionally dense and
contains exact enum, permission, and event identifiers from pos-price source.

## Promotions Tokens

PromotionStatus:

- DRAFT
- ACTIVE
- INACTIVE
- EXPIRED

DiscountType:

- PERCENT_LABOR
- PERCENT_PARTS
- FIXED_INVOICE

ConditionType:

- ACCOUNT_ID_LIST
- VEHICLE_TAG
- ACCOUNT_FLEET_SIZE

RuleOperator:

- IN
- NOT_IN
- EQUALS
- GREATER_THAN_OR_EQUAL_TO

RuleCombination:

- AND
- OR

EligibilityReasonCode:

- ELIGIBLE
- ACCOUNT_NOT_IN_LIST
- ACCOUNT_IN_EXCLUSION_LIST
- VEHICLE_TAG_NOT_PRESENT
- VEHICLE_TAG_EXCLUDED
- FLEET_SIZE_TOO_SMALL
- MISSING_ACCOUNT_CONTEXT
- MISSING_VEHICLE_CONTEXT
- EVALUATION_ERROR

## Restrictions Tokens

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

## Permissions From permissions.yaml

- pricing:base_price:create
- pricing:normalization:edit
- pricing:normalization:view
- pricing:price_book:create
- pricing:price_book:delete
- pricing:price_book:edit
- pricing:price_book:view
- pricing:promotion:apply
- pricing:promotion:manage
- pricing:promotion:view
- pricing:restriction:manage
- pricing:restriction:override
- pricing:restrictions:edit
- pricing:restrictions:view
- pricing:rule:create
- pricing:rule:delete
- pricing:rule:edit
- pricing:rule:view

## Event Tokens

- PRICE_NORMALIZATION_NORMALIZE
- PRICE_BULK_INGEST
- PRICE_RESTRICTIONS_EVALUATE
- PRICE_RESTRICTIONS_OVERRIDE
- PRICE_RESTRICTION_RULE_CREATE
- PRICE_RESTRICTION_RULE_DEACTIVATE
- PRICE_QUOTE_CALCULATE
- PROMOTION_OFFER_CREATE
- PROMOTION_OFFER_ACTIVATE
- PROMOTION_OFFER_DEACTIVATE
- PROMOTION_OFFER_APPLY
- PROMOTION_RULE_CREATE
- PROMOTION_RULE_DELETE
- PROMOTION_RULE_EVALUATE

## Verified Facts

- _Verified: pos-price enums under internal/enums for all listed promotion and restriction tokens._
- _Verified: pos-price permissions.yaml contains all listed pricing permission codes._
- _Verified: pos-price EventTypes.all contains all listed price and promotion event type codes._
