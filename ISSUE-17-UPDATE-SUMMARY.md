# Issue #17 Update Summary: Clarification Resolution

## Context
- **Origin Story:** Issue #17 - [BACKEND] [STORY] Catalog: Search Catalog by Keyword/SKU and Filter
- **Clarification Issue:** Issue #219 - [CLARIFICATION] Origin #17
- **Status:** All clarification questions answered by @louisburroughs on 2026-01-12
- **Updated By:** Story Authoring Agent
- **Date:** 2026-01-13

## Clarification Questions Resolved

### Question 1: Performance Target Latency
**Question:** What is the specific, measurable target latency for search results (e.g., P95 response time < 500ms)?

**Answer:**
- **P95 < 500 ms** end-to-end for keyword search (API response time), measured at the service boundary
- **P99 < 1000 ms**
- **P50 < 200 ms**
- Targets assume indexed search (DB indexes or search engine) and warm caches
- For search + pricing/inventory enrichment: P95 < 900 ms with graceful degradation when dependencies time out

### Question 2: Search Fields (Keyword Matching)
**Question:** What are the specific product fields the general "keyword" search should match against?

**Answer:**
Keyword search should match against the following fields, in priority order:
1. **SKU / Product Code** (exact + prefix)
2. **Product Name** (tokenized contains)
3. **Manufacturer Part Number (MPN)** (exact + prefix + tokenized)
4. **Manufacturer / Brand Name** (tokenized)
5. **Tags / Categories** (tokenized)
6. **Short Description** (tokenized, lower weight)

**Explicit exclusions:**
- **Long Description** excluded at launch (too noisy, expensive, relevance issues)
- Free-form notes excluded

**Matching behavior:**
- Normalize: lowercase, strip punctuation, collapse whitespace
- Rank: exact matches > prefix > token contains
- Weighting: identifiers (SKU/MPN) highest; descriptions lowest

### Question 3: Pagination Policy
**Question:** What is the default page size for search results, and what is the maximum allowable page size?

**Answer:**
- **Default page size:** 25
- **Maximum page size:** 100 (hard cap)
- Allow client override via `pageSize` parameter within bounds
- Prefer **cursor-based pagination** for stability and performance:
  - request: `cursor`, `pageSize`
  - response: `nextCursor`
- If offset is required for a specific UI, support it separately, but cursor should be the primary API

## Changes Made to Story

### 1. Business Rules Section - Enhanced
Added detailed specifications for:
- **Keyword Matching:** Complete list of searchable fields with priority order
- **Exclusions:** Explicit list of excluded fields
- **Normalization and Ranking:** How search terms are processed
- **Pagination Policy:** Default size (25), max size (100), cursor-based mechanism
- **Performance Requirements:** P95/P99/P50 targets with context

### 2. Data Requirements Section - Updated
- Updated `SearchQuery` pagination to use `cursor` and `page_size`
- Updated `SearchResult` metadata to include `next_cursor` instead of `total_pages` and `current_page`

### 3. Acceptance Criteria - Expanded
Added new acceptance criteria:
- **AC4: Pagination Default** - Verifies default page size of 25
- **AC5: Pagination Maximum** - Verifies max page size cap of 100
- **AC7: Performance Target** - Verifies P95 < 500ms and P99 < 1000ms
- **AC8: Keyword Field Matching** - Verifies tokenized matching on Product Name
- **AC9: Exclusion of Long Description** - Verifies Long Description is not searchable

### 4. Audit & Observability Section - Enhanced
Added new metric:
- `search_results_count`: A histogram of the number of results returned per search

### 5. Removed "Open Questions" Section
All three questions have been answered and incorporated into the story.

### 6. Added "Clarification History" Section
Documents the clarification process with:
- Link to clarification issue #219
- Summary of questions resolved
- Who answered them and when

## Implementation Impact

### Ready for Development
The story is now **implementation-ready** with:
- ✅ All performance targets specified
- ✅ All search fields and exclusions defined
- ✅ Pagination mechanism fully specified
- ✅ Testable acceptance criteria
- ✅ No open questions or ambiguities

### Key Implementation Guidance

#### Search Implementation
1. Create indexed fields for: SKU, name, MPN, manufacturer, tags, categories, short_description
2. Implement tokenization and normalization for keyword matching
3. Implement ranking algorithm with weighting (identifiers > descriptions)
4. Exclude long_description and notes from search indexes

#### Pagination Implementation
1. Primary: Implement cursor-based pagination
2. Cursors should encode position in result set
3. Enforce page_size bounds: default=25, max=100
4. Return `next_cursor` as null when no more pages

#### Performance Targets
1. Add database indexes on all searchable fields
2. Use search engine (Elasticsearch/OpenSearch) if available
3. Implement caching for common queries
4. Monitor P95/P99/P50 latencies
5. Consider separate endpoints for enriched searches

## Next Steps

### For Manual Execution
1. Update issue #17 body with content from `ISSUE-17-UPDATED-BODY.md`
2. Update labels on issue #17:
   - Remove: `blocked:clarification`, `status:draft`
   - Add: `status:ready-for-dev`
3. Post handoff comment on issue #17 (see `ISSUE-17-HANDOFF-COMMENT.md`)
4. Close clarification issue #219 with completion note (see `ISSUE-219-CLOSE-COMMENT.md`)

### For Automated Execution
Run the provided script:
```bash
GH_TOKEN=<your_token> ./update-issue-17.sh
```

This will:
- Update issue #17 body
- Update labels
- Post handoff comment
- Close issue #219

## Related Files
- `ISSUE-17-UPDATED-BODY.md` - New body for issue #17
- `ISSUE-17-HANDOFF-COMMENT.md` - Handoff comment for issue #17
- `ISSUE-219-CLOSE-COMMENT.md` - Close comment for issue #219
- `update-issue-17.sh` - Automation script (requires GH_TOKEN)
