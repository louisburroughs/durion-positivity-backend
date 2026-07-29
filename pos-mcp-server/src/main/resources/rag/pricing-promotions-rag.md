---
rag_id: pricing.promotions
rag_scope: pricing
required_permissions:
  - pricing:promotion:view
---

## Purpose

RAG id: pricing.promotions
RAG scope: pricing
Required permissions: pricing:promotion:view
Audience: internal staff.

This document covers implemented promotion offers and eligibility rules in pos-price,
including lifecycle gates, rule evaluation behavior, permissions, and event tokens.

## Endpoints, Permissions, and Events

| Operation | Method and path | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Create offer | POST /v1/promotions/offers | pricing:promotion:manage | PROMOTION_OFFER_CREATE |
| Get offer | GET /v1/promotions/offers/{id} | pricing:promotion:view | none |
| Get offer by code | GET /v1/promotions/offers/by-code/{promoCode} | pricing:promotion:view | none |
| Activate offer | PATCH /v1/promotions/offers/{id}/activate | pricing:promotion:manage | PROMOTION_OFFER_ACTIVATE |
| Deactivate offer | PATCH /v1/promotions/offers/{id}/deactivate | pricing:promotion:manage | PROMOTION_OFFER_DEACTIVATE |
| Apply offer | POST /v1/promotions/offers/apply | pricing:promotion:apply | PROMOTION_OFFER_APPLY |
| Create eligibility rule | POST /v1/promotions/offers/{promotionId}/rules | pricing:promotion:manage | PROMOTION_RULE_CREATE |
| List eligibility rules | GET /v1/promotions/offers/{promotionId}/rules | pricing:promotion:view | none |
| Delete eligibility rule | DELETE /v1/promotions/offers/{promotionId}/rules/{ruleId} | pricing:promotion:manage | PROMOTION_RULE_DELETE |
| Evaluate eligibility | POST /v1/promotions/offers/{promotionId}/rules/evaluate | pricing:promotion:apply | PROMOTION_RULE_EVALUATE |

## Promotion Apply Constraints

Promotion application enforces all of the following:

- Exactly one promo per estimate: request is rejected when appliedPromoCodes is not empty.
- Offer must exist by promoCode.
- Offer status must be ACTIVE.
- Offer must be in date window (startDate <= today <= endDate).
- Eligibility must pass rule evaluation.
- Usage count must remain under usageLimit.

Discount computation types:

- PERCENT_LABOR
- PERCENT_PARTS
- FIXED_INVOICE

## Eligibility Evaluation Behavior

Rule evaluation uses ruleCombination from the first rule (default AND when null):

- AND: first failing rule returns a non-eligible response.
- OR: first passing rule returns eligible; when none pass, returns last failure reason.
- No rules: returns eligible with ELIGIBLE reason.

Supported condition/operator combinations:

- ACCOUNT_ID_LIST with IN and NOT_IN
- ACCOUNT_FLEET_SIZE with GREATER_THAN_OR_EQUAL_TO
- VEHICLE_TAG with EQUALS and NOT_IN

## Enum and Token Catalog

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

## Verified Facts

- _Verified: pos-price PromotionOfferController and PromotionEligibilityRuleController endpoint mappings, PreAuthorize codes, and EmitEvent ids._
- _Verified: pos-price PromotionOfferServiceImpl.applyPromotion enforces status/date/usage/eligibility and one-promo-per-estimate constraints._
- _Verified: pos-price EligibilityEvaluationServiceImpl applies AND/OR evaluation with the supported condition/operator matrix and no-rules eligible behavior._
- _Verified: pos-price enums PromotionStatus, DiscountType, ConditionType, RuleOperator, RuleCombination, and EligibilityReasonCode._
