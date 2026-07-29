---
title: Research Pricing Wave 2 Anchors
owner: pos-mcp-server
date: 2026-07-29
scope: pos-price only
issue: 1124
---

## Scope

- Objective: source-verified anchors for Wave 2 pricing docs in issue #1124.
- Covered doc targets: pricing.promotions, pricing.restrictions, pricing.codes.
- Source boundary enforced: pos-price module only under durion-positivity-backend.
- Evidence model: implementation files first, tests as behavioral confirmation.

## Verified Source Inventory

| Source file | Why it was used |
| --- | --- |
| [pos-price/src/main/java/com/positivity/price/internal/controller/PromotionOfferController.java](../../pos-price/src/main/java/com/positivity/price/internal/controller/PromotionOfferController.java) | Promotion offer endpoints, permissions, event ids |
| [pos-price/src/main/java/com/positivity/price/internal/controller/PromotionEligibilityRuleController.java](../../pos-price/src/main/java/com/positivity/price/internal/controller/PromotionEligibilityRuleController.java) | Eligibility rule endpoints, evaluate endpoint, permissions, event ids |
| [pos-price/src/main/java/com/positivity/price/internal/service/PromotionOfferServiceImpl.java](../../pos-price/src/main/java/com/positivity/price/internal/service/PromotionOfferServiceImpl.java) | applyPromotion decision logic and constraints |
| [pos-price/src/main/java/com/positivity/price/internal/service/EligibilityEvaluationServiceImpl.java](../../pos-price/src/main/java/com/positivity/price/internal/service/EligibilityEvaluationServiceImpl.java) | evaluateEligibility logic, rule combination behavior, reason codes |
| [pos-price/src/main/java/com/positivity/price/internal/repository/PromotionOfferRepository.java](../../pos-price/src/main/java/com/positivity/price/internal/repository/PromotionOfferRepository.java) | usage limit enforcement query |
| [pos-price/src/main/java/com/positivity/price/internal/controller/RestrictionRuleController.java](../../pos-price/src/main/java/com/positivity/price/internal/controller/RestrictionRuleController.java) | Restriction rule endpoints, permissions, event ids |
| [pos-price/src/main/java/com/positivity/price/internal/controller/PriceRestrictionsController.java](../../pos-price/src/main/java/com/positivity/price/internal/controller/PriceRestrictionsController.java) | restrictions evaluate and override endpoints, permissions, event ids |
| [pos-price/src/main/java/com/positivity/price/internal/service/RestrictionEvaluationServiceImpl.java](../../pos-price/src/main/java/com/positivity/price/internal/service/RestrictionEvaluationServiceImpl.java) | restriction decision outcomes, timeout and commit-path behavior |
| [pos-price/src/main/java/com/positivity/price/internal/service/RestrictionOverrideServiceImpl.java](../../pos-price/src/main/java/com/positivity/price/internal/service/RestrictionOverrideServiceImpl.java) | override issuance lifecycle and expiry behavior |
| [pos-price/src/main/java/com/positivity/price/internal/entity/RestrictionOverrideAudit.java](../../pos-price/src/main/java/com/positivity/price/internal/entity/RestrictionOverrideAudit.java) | persisted override fields and status representation |
| [pos-price/src/main/java/com/positivity/price/internal/repository/RestrictionRuleRepository.java](../../pos-price/src/main/java/com/positivity/price/internal/repository/RestrictionRuleRepository.java) | active/location/service scoped rule lookup query shape |
| [pos-price/src/main/java/com/positivity/price/internal/config/EventTypes.java](../../pos-price/src/main/java/com/positivity/price/internal/config/EventTypes.java) | canonical event type code list |
| [pos-price/src/main/resources/permissions.yaml](../../pos-price/src/main/resources/permissions.yaml) | declared permission catalog |
| [pos-price/src/main/java/com/positivity/price/internal/enums/*.java](../../pos-price/src/main/java/com/positivity/price/internal/enums) | enum token extraction for promotions and restrictions |
| [pos-price/src/test/java/com/positivity/price/internal/service/PromotionOfferServiceImplTest.java](../../pos-price/src/test/java/com/positivity/price/internal/service/PromotionOfferServiceImplTest.java) | validates applyPromotion constraints and failure reasons |
| [pos-price/src/test/java/com/positivity/price/internal/service/EligibilityEvaluationServiceImplTest.java](../../pos-price/src/test/java/com/positivity/price/internal/service/EligibilityEvaluationServiceImplTest.java) | validates eligibility rule behavior and no-rules path |
| [pos-price/src/test/java/com/positivity/price/internal/service/RestrictionEvaluationServiceImplTest.java](../../pos-price/src/test/java/com/positivity/price/internal/service/RestrictionEvaluationServiceImplTest.java) | validates ALLOW/BLOCK/ALLOW_WITH_OVERRIDE/UNKNOWN and commit failure semantics |
| [pos-price/src/test/java/com/positivity/price/internal/service/RestrictionOverrideServiceImplTest.java](../../pos-price/src/test/java/com/positivity/price/internal/service/RestrictionOverrideServiceImplTest.java) | validates override response, actor provenance, audit persistence |

## pricing.promotions facts

### Controllers and endpoints for offers and eligibility rules

| Area | Endpoint | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Promotion offers | POST /v1/promotions/offers | pricing:promotion:manage | PROMOTION_OFFER_CREATE |
| Promotion offers | GET /v1/promotions/offers/{id} | pricing:promotion:view | none |
| Promotion offers | GET /v1/promotions/offers/by-code/{promoCode} | pricing:promotion:view | none |
| Promotion offers | PATCH /v1/promotions/offers/{id}/activate | pricing:promotion:manage | PROMOTION_OFFER_ACTIVATE |
| Promotion offers | PATCH /v1/promotions/offers/{id}/deactivate | pricing:promotion:manage | PROMOTION_OFFER_DEACTIVATE |
| Promotion offers | POST /v1/promotions/offers/apply | pricing:promotion:apply | PROMOTION_OFFER_APPLY |
| Eligibility rules | POST /v1/promotions/offers/{promotionId}/rules | pricing:promotion:manage | PROMOTION_RULE_CREATE |
| Eligibility rules | GET /v1/promotions/offers/{promotionId}/rules | pricing:promotion:view | none |
| Eligibility rules | DELETE /v1/promotions/offers/{promotionId}/rules/{ruleId} | pricing:promotion:manage | PROMOTION_RULE_DELETE |
| Eligibility rules | POST /v1/promotions/offers/{promotionId}/rules/evaluate | pricing:promotion:apply | PROMOTION_RULE_EVALUATE |

### Service behavior: applyPromotion

Verified in PromotionOfferServiceImpl and tests:

- One promotion per estimate is enforced by checking estimateContext.appliedPromoCodes is empty; otherwise throws PromotionMultipleNotAllowedException.
- Promotion code must resolve; missing code throws PromotionCodeNotFoundException.
- Offer must be ACTIVE; otherwise throws PromotionNotApplicableException.
- Date window check is enforced against LocalDate.now(clock): startDate <= today <= endDate.
- Eligibility is delegated to evaluateEligibility(promotionOfferId, customerId, vehicleId); non-eligible result throws PromotionNotApplicableException with reason code.
- Usage limit is enforced atomically via incrementUsageCountIfUnderLimit; update count 0 causes PromotionNotApplicableException for limit reached.
- Discount computation behavior:
  - PERCENT_LABOR and PERCENT_PARTS apply percentage against subtotal and negate as discount.
  - FIXED_INVOICE applies fixed value and negates as discount.

### Service behavior: evaluateEligibility

Verified in EligibilityEvaluationServiceImpl and tests:

- If no rules exist for promotionId, returns eligible with ELIGIBLE reason.
- Rule combination is taken from first rule; null defaults to AND.
- OR behavior: returns on first passing rule, otherwise returns last failure reason.
- AND behavior: returns first failure, otherwise ELIGIBLE after full pass.
- Supported operator matrix:
  - ACCOUNT_ID_LIST supports IN and NOT_IN.
  - ACCOUNT_FLEET_SIZE supports GREATER_THAN_OR_EQUAL_TO.
  - VEHICLE_TAG supports EQUALS and NOT_IN.
- Unsupported operator for a condition yields EVALUATION_ERROR.
- Missing required account or vehicle context yields MISSING_ACCOUNT_CONTEXT or MISSING_VEHICLE_CONTEXT.
- This service does not enforce promotion status, date window, or usage limits; those checks are in applyPromotion.

### Enums requested for promotions

- PromotionStatus: DRAFT, ACTIVE, INACTIVE, EXPIRED.
- DiscountType: PERCENT_LABOR, PERCENT_PARTS, FIXED_INVOICE.
- ConditionType: ACCOUNT_ID_LIST, VEHICLE_TAG, ACCOUNT_FLEET_SIZE.
- RuleOperator: IN, NOT_IN, EQUALS, GREATER_THAN_OR_EQUAL_TO.
- RuleCombination: AND, OR.
- EligibilityReasonCode: ELIGIBLE, ACCOUNT_NOT_IN_LIST, ACCOUNT_IN_EXCLUSION_LIST, VEHICLE_TAG_NOT_PRESENT, VEHICLE_TAG_EXCLUDED, FLEET_SIZE_TOO_SMALL, MISSING_ACCOUNT_CONTEXT, MISSING_VEHICLE_CONTEXT, EVALUATION_ERROR.

## pricing.restrictions facts

### RestrictionRuleController and PriceRestrictionsController endpoints

| Area | Endpoint | Permission | EmitEvent id |
| --- | --- | --- | --- |
| Restriction rules | POST /v1/price/restrictions/rules | pricing:restriction:manage | PRICE_RESTRICTION_RULE_CREATE |
| Restriction rules | GET /v1/price/restrictions/rules/{ruleId} | isAuthenticated | none |
| Restriction rules | GET /v1/price/restrictions/rules | isAuthenticated | none |
| Restriction rules | DELETE /v1/price/restrictions/rules/{ruleId} | pricing:restriction:manage | PRICE_RESTRICTION_RULE_DEACTIVATE |
| Restriction evaluation | POST /v1/price/restrictions:evaluate | isAuthenticated | PRICE_RESTRICTIONS_EVALUATE |
| Restriction override | POST /v1/price/restrictions:override | pricing:restriction:override | PRICE_RESTRICTIONS_OVERRIDE |

### RestrictionEvaluationServiceImpl decision behavior

Verified in implementation and tests:

- evaluate(request) returns one result per item in request.items.
- For each item, matching rules are queried by productId + active + locationTag + serviceTag.
- Default/no-rules path returns decision ALLOW with empty rule id list.
- If any matching rule has overrideable=false, decision is BLOCK.
- If matching rules exist and all are overrideable=true, decision is ALLOW_WITH_OVERRIDE.
- Execution uses CompletableFuture with per-item timeout (default 800 ms).
- Failure handling is context-sensitive:
  - Commit path contexts CHECKOUT, INVOICE_FINALIZE, COMMIT_SALE throw RestrictionServiceUnavailableException.
  - Non-commit contexts return RESTRICTION_UNKNOWN.

### RestrictionOverrideServiceImpl override lifecycle and expiry facts

Verified in implementation and tests:

- createOverride requires existing ruleId; missing rule throws RestrictionRuleNotFoundException.
- New RestrictionOverrideAudit is persisted with:
  - status set to ISSUED.
  - issuedAt = now(clock).
  - expiresAt = issuedAt + 24 hours.
  - policyVersion copied from current rule.
  - actor sourced from SecurityContextHelper current user (tests verify actor from security context).
- Response returns overrideId and expiresAt only.
- No approve or deny transition method exists in this service implementation.

### Enums requested for restrictions

- RestrictionDecision: ALLOW, BLOCK, ALLOW_WITH_OVERRIDE, RESTRICTION_UNKNOWN.
- EvaluationContext: BROWSE, QUOTE, CHECKOUT, INVOICE_FINALIZE, COMMIT_SALE.
- LocationTag: ALL_LOCATIONS, RETAIL_STORE, WAREHOUSE, MOBILE_SERVICE, FRANCHISE, TEST_LOCATION.
- ServiceTag: POS_SALE, WORKORDER, ESTIMATE, INVOICE, DELIVERY.
- OverrideStatus: ISSUED, APPROVED, DENIED.

## pricing.codes token catalog seed lists

### Enum token seeds

- PromotionStatus: DRAFT, ACTIVE, INACTIVE, EXPIRED
- DiscountType: PERCENT_LABOR, PERCENT_PARTS, FIXED_INVOICE
- ConditionType: ACCOUNT_ID_LIST, VEHICLE_TAG, ACCOUNT_FLEET_SIZE
- RuleOperator: IN, NOT_IN, EQUALS, GREATER_THAN_OR_EQUAL_TO
- RuleCombination: AND, OR
- EligibilityReasonCode: ELIGIBLE, ACCOUNT_NOT_IN_LIST, ACCOUNT_IN_EXCLUSION_LIST, VEHICLE_TAG_NOT_PRESENT, VEHICLE_TAG_EXCLUDED, FLEET_SIZE_TOO_SMALL, MISSING_ACCOUNT_CONTEXT, MISSING_VEHICLE_CONTEXT, EVALUATION_ERROR
- RestrictionDecision: ALLOW, BLOCK, ALLOW_WITH_OVERRIDE, RESTRICTION_UNKNOWN
- EvaluationContext: BROWSE, QUOTE, CHECKOUT, INVOICE_FINALIZE, COMMIT_SALE
- LocationTag: ALL_LOCATIONS, RETAIL_STORE, WAREHOUSE, MOBILE_SERVICE, FRANCHISE, TEST_LOCATION
- ServiceTag: POS_SALE, WORKORDER, ESTIMATE, INVOICE, DELIVERY
- OverrideStatus: ISSUED, APPROVED, DENIED

### Permission token seeds from permissions.yaml

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

### Permission tokens actively enforced by @PreAuthorize in main controllers

- pricing:base_price:create
- pricing:promotion:apply
- pricing:promotion:manage
- pricing:promotion:view
- pricing:restriction:manage
- pricing:restriction:override

### Event id seeds from EventTypes and @EmitEvent usage

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

## Declared-but-unused or mismatch notes

- Permission singular versus plural mismatch exists in declared catalog:
  - Active controller checks use pricing:restriction:* (singular).
  - permissions.yaml also declares pricing:restrictions:edit and pricing:restrictions:view (plural), which are not used by current @PreAuthorize checks in main source.
- Additional declared permissions without current @PreAuthorize use in main source:
  - pricing:normalization:edit, pricing:normalization:view
  - pricing:price_book:create, pricing:price_book:delete, pricing:price_book:edit, pricing:price_book:view
  - pricing:rule:create, pricing:rule:delete, pricing:rule:edit, pricing:rule:view
- OverrideStatus includes APPROVED and DENIED, but current override service sets ISSUED only and does not implement transitions.
- PromotionStatus includes EXPIRED, but PromotionOfferServiceImpl does not set status to EXPIRED in current lifecycle methods.

## Open risks and ambiguities

- Restriction rule evaluation query does not include effectiveFrom or effectiveTo filtering; rule date fields exist on entity, but runtime decision currently depends on active + tags + product only.
- Promotion eligibility endpoint and service evaluate only rule predicates; promotion lifecycle constraints (ACTIVE/date/usage) are enforced in applyPromotion, not in evaluateEligibility.
- Restriction evaluate endpoint advertises commit-path 503 behavior; this is implemented through context-based exception flow, but caller behavior for retry/circuit handling is outside this module.
- Declared but unused permissions increase risk of authorization catalog drift unless reconciled with intended endpoint coverage.
