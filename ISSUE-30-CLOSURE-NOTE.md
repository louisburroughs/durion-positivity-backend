# Clarification Resolution Complete

This clarification issue has been **fully resolved** and incorporated into the origin story.

## Resolution Summary

All five questions have been answered with clear, deterministic recommendations:

### 1. Decision Hierarchy
- **Default presentation order:** Substitute → External → Backorder
- **Intra-category ranking:** Lead Time ASC → Cost ASC → Quality Tier DESC → Brand Preference
- **Configuration:** `shortageDecisionOrder = [SUBSTITUTE, EXTERNAL, BACKORDER]` with per-location override

### 2. Product Domain Data Contract
- **Endpoint:** `POST /product/v1/substitutes:resolve`
- **Capabilities:** Batch requests, vehicle context, fitment confidence
- **Response fields:** qualityTier, brand, fitmentConfidence, priceDifference, notes

### 3. Positivity Domain Data Contract
- **Endpoint:** `POST /positivity/v1/availability/external`
- **Capabilities:** Batch requests, multi-source aggregation
- **Response fields:** sourceType, availableQuantity, estimatedLeadTimeDays, additionalCost, confidence

### 4. Error Handling Policy
- **Product timeout:** 800 ms (hard limit)
- **Positivity timeout:** 1200 ms (hard limit)
- **Behavior:** Graceful degradation, no synchronous retries, user notification via banner

### 5. Backorder Lead Time Sourcing
- **Tiered fallback:** Purchasing → Inventory → Catalog
- **Required fields:** estimatedLeadTimeDays, source, confidence
- **Policy:** Omit backorder if no source exists (never fabricate)

## Impact on Origin Story

The origin story (issue #25) has been updated with:
- Complete Product and Positivity domain integration schemas
- Deterministic option presentation and ranking rules
- Specific timeout thresholds and error handling behavior
- Enhanced acceptance criteria covering all clarification aspects
- Removal of the "Open Questions" section

## Story Status

✅ **Origin story #25 is now implementation-ready**

Labels updated:
- ❌ Removed: `blocked:clarification`, `status:draft`
- ✅ Added: `status:ready-for-dev`

The story is ready for assignment to development.

---

**Origin Story:** #25
**Clarification Type:** clarification:data
**Domain:** domain:inventory
**Resolved By:** @louisburroughs
**Date:** 2026-01-13

This issue is now closed. All decisions have been incorporated into the origin story.
