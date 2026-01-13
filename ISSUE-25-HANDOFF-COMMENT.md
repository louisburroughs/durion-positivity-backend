# ✅ Story Ready for Development

## Clarification Resolution Complete

All open questions from clarification issue #30 have been resolved and integrated into this story.

### Resolved Questions

1. **Decision Hierarchy** ✅
   - Options presented in deterministic order: Substitute → External → Backorder
   - Within each category, ranked by: Lead Time ASC → Cost ASC → Quality Tier DESC → Brand Preference
   - Configurable via `shortageDecisionOrder` with per-location override

2. **Product Domain Data Contract** ✅
   - Endpoint: `POST /product/v1/substitutes:resolve`
   - Batch-capable API with vehicle context support
   - Returns quality tier, fitment confidence, price differences, and brand information
   - See updated Data Requirements section for complete schemas

3. **Positivity Domain Data Contract** ✅
   - Endpoint: `POST /positivity/v1/availability/external`
   - Batch-capable API
   - Returns source type, available quantity, lead time, additional cost, and confidence
   - See updated Data Requirements section for complete schemas

4. **Error Handling Policy** ✅
   - Product Domain timeout: **800 ms**
   - Positivity Domain timeout: **1200 ms**
   - Graceful degradation: omit failed category, show banner to user
   - No synchronous retries

5. **Backorder Lead Time Sourcing** ✅
   - Tiered fallback: Purchasing Domain → Inventory Domain → Product Catalog
   - Must include `source` and `confidence` fields
   - Omit backorder option if no source exists (never fabricate)

### Changes Integrated

- ✅ Business Rules updated with decision hierarchy and timeout thresholds
- ✅ Data Requirements expanded with complete Product and Positivity domain schemas
- ✅ Alternate/Error Flows updated with specific timeout values and degradation behavior
- ✅ Acceptance Criteria enhanced with option ranking and lead time source scenarios
- ✅ Open Questions section removed

### Story Status

**This story is now implementation-ready.**

All acceptance criteria are testable, domain contracts are specified, and error handling policies are deterministic. A developer can implement this without guessing, and a tester can derive tests directly from the story.

---

### Next Steps

This story is ready for assignment to:
- `@github-copilot` for code generation support
- Principal Software Engineer Agent for technical execution

---

**Related Issues:**
- Clarification Issue: #30 (resolved and will be closed)
