# ✅ Story Refinement Complete — Ready for Development

## Clarification Resolution Summary

All open questions from the original story have been resolved based on clarification issue #221. The business decisions are now integrated into the story and documented in the "Resolved Business Decisions" section.

### Decisions Integrated:

1. **Inventory Policy (Insufficient Stock)**
   - ✅ Allow item addition with backorder flag (`WARN_AND_BACKORDER`)
   - ✅ Display clear warning to clerk
   - ✅ Configurable per policy with per-item override support

2. **Work Order/Estimate Linking**
   - ✅ Merge items into current cart (not replace)
   - ✅ Merge quantities for same SKU+price; add separate lines for different prices
   - ✅ Preserve source references (`sourceType`, `sourceId`, `sourceLineId`)
   - ✅ Idempotent re-linking

3. **Anonymous Cart Support**
   - ✅ Anonymous carts are valid
   - ✅ Clear feature limitations documented (no customer-specific promotions, invoicing, etc. until customer assigned)
   - ✅ Re-evaluation of pricing/taxes/policies when customer is set

4. **Pricing Service Dependency**
   - ✅ Soft dependency with bounded fallback
   - ✅ Cached pricing (TTL: 60s) with `STALE` marking
   - ✅ Manual price entry with permission, reason code, and audit
   - ✅ Silent fallback explicitly disallowed

## Story Status

- **Previous Status:** `status:draft`, `blocked:clarification`
- **Current Status:** `status:ready-for-dev`
- **Clarification Issue:** #221 (now closed)

## Next Steps

This story is now ready for technical implementation. The acceptance criteria are testable, business rules are explicit, and all ambiguities have been resolved.

**Assigned to:**
- @github-copilot (for code generation support)

**Reference Documents:**
- Clarification Issue: #221
- Clarification Resolution: See user comment in #221 dated 2026-01-13

---

**Story Authoring Agent** | Refinement completed on 2026-01-13
