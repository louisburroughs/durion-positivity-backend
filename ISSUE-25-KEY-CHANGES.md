# Key Changes Summary - Issue #25 Update

## Overview
This document highlights the key changes made to issue #25 based on clarification resolution #30.

---

## 1. Business Rules Section - NEW CONTENT ADDED

### Decision Hierarchy (NEW)
```markdown
- **Decision Hierarchy (Option Presentation Order):**
  - Shortage resolution options are presented in this deterministic order:
    1. **Substitute parts** (preserves service continuity with minimal delay)
    2. **External availability** (maintains original spec but may add logistics cost)
    3. **Backorder** (least desirable operationally, always shown as fallback)
  - Within each option category, options are ranked by:
    1. **Availability / Lead Time ASC** (fastest first)
    2. **Total Cost Impact ASC** (price difference + handling)
    3. **Quality Tier DESC** (OEM > Equivalent > Aftermarket)
    4. **Brand Preference** (customer or shop preference, optional)
  - Configuration: `shortageDecisionOrder = [SUBSTITUTE, EXTERNAL, BACKORDER]` (default), 
    with per-location override allowed.
```

### Error Handling Policy (NEW)
```markdown
- **Error Handling Policy:**
  - Product Domain timeout threshold: **800 ms**
  - Positivity Domain timeout threshold: **1200 ms**
  - No synchronous retries; background refresh allowed for UI updates.
  - Degradation: Omit failed option category, present remaining options with informational banner.
```

### Backorder Lead Time Sourcing (NEW)
```markdown
- **Backorder Lead Time Sourcing:**
  - Use tiered fallback model for `estimatedLeadTimeDays`:
    1. **Purchasing / Supplier domain** (if integrated) — authoritative
    2. **Inventory domain replenishment estimate** — preferred default
    3. **Product catalog static lead-time hint** — last resort
  - Lead time must always include `source` and `confidence` fields.
  - If no source exists, omit the backorder option rather than fabricating a lead time.
```

---

## 2. Data Requirements Section - MAJOR EXPANSION

### Product Domain Integration (NEW)

**Endpoint:** `POST /product/v1/substitutes:resolve`

**Request Schema:**
```json
{
  "items": [
    {
      "productId": "UUIDv7",
      "quantity": 2,
      "context": {
        "vehicleAttributes": {
          "make": "Ford",
          "model": "F-150",
          "year": 2022
        },
        "locationId": "UUIDv7"
      }
    }
  ],
  "includePricing": true
}
```

**Response Schema:**
```json
{
  "results": [
    {
      "productId": "UUIDv7",
      "substitutes": [
        {
          "substituteProductId": "UUIDv7",
          "qualityTier": "OEM | EQUIVALENT | AFTERMARKET",
          "brand": "string",
          "fitmentConfidence": "HIGH | MEDIUM | LOW",
          "priceDifference": {
            "amount": 12.50,
            "currency": "USD"
          },
          "notes": "string"
        }
      ]
    }
  ]
}
```

### Positivity Domain Integration (NEW)

**Endpoint:** `POST /positivity/v1/availability/external`

**Request Schema:**
```json
{
  "items": [
    {
      "productId": "UUIDv7",
      "quantity": 2,
      "deliveryLocationId": "UUIDv7"
    }
  ]
}
```

**Response Schema:**
```json
{
  "results": [
    {
      "productId": "UUIDv7",
      "sources": [
        {
          "sourceId": "string",
          "sourceType": "SUPPLIER | PARTNER_SHOP",
          "availableQuantity": 4,
          "estimatedLeadTimeDays": 1,
          "additionalCost": {
            "amount": 35.00,
            "currency": "USD"
          },
          "confidence": "HIGH | MEDIUM | LOW"
        }
      ]
    }
  ]
}
```

---

## 3. Alternate/Error Flows - UPDATED

**BEFORE:**
```markdown
- **Integration Partner Timeout/Error:** If a request to a dependent service (Product, 
  Positivity) fails or times out, the system shall proceed with the data it has.
```

**AFTER:**
```markdown
- **Integration Partner Timeout/Error:** 
  - If a request to the **Product Domain** exceeds **800 ms**, the system will proceed 
    without substitute data.
  - If a request to the **Positivity Domain** exceeds **1200 ms**, the system will proceed 
    without external availability data.
  - The system shall **not** fail the entire operation due to dependent service failures.
  - Timeouts and failures will be logged, and the user will be presented with the remaining 
    valid options.
  - A banner message shall be displayed: _"Some availability options could not be retrieved 
    at this time."_
```

---

## 4. Functional Behavior - UPDATED

**Step 5 - BEFORE:**
```markdown
5. The system returns a structured response to the Point of Sale (POS) client containing 
   all available options:
   - Option 1: Backorder the original part.
   - Option 2: A list of one or more substitute parts (if any)...
   - Option 3: A list of external availability options (if any)...
```

**Step 5 - AFTER:**
```markdown
5. The system returns a structured response to the Point of Sale (POS) client containing 
   all available options, presented in the following **deterministic order**:
   - **Option 1: Substitute parts** (if available)
   - **Option 2: External availability** (if available)
   - **Option 3: Backorder** the original part (always available as fallback)
```

---

## 5. Acceptance Criteria - NEW SCENARIOS ADDED

### Scenario 5: Option Ranking within Category (NEW)
```markdown
**Scenario 5: Option Ranking within Category**
- **Given** a part with SKU "MNO-707" has an ATP of 0
- **And** the Product domain returns three substitute options with varying lead times and costs
- **When** the system processes the allocation request
- **Then** substitutes must be sorted by:
    1. Lead time (ascending)
    2. Total cost impact (ascending)
    3. Quality tier (descending: OEM > EQUIVALENT > AFTERMARKET)
```

### Scenario 6: Backorder with Lead Time Source (NEW)
```markdown
**Scenario 6: Backorder with Lead Time Source**
- **Given** a part with SKU "PQR-808" has an ATP of 0
- **And** no substitutes or external options are available
- **When** the system presents the backorder option
- **Then** the response must include `estimatedLeadTimeDays`, `source`, and `confidence` fields
- **And** if no lead time source exists, the backorder option must be omitted (no fabricated lead time)
```

### Scenario 4: Integration Partner Fails - UPDATED
**BEFORE:**
```markdown
- **And** the request to the Product domain service times out
```

**AFTER:**
```markdown
- **And** the request to the Product domain service times out (exceeds 800ms)
- **And** it must display the banner: "Some availability options could not be retrieved at this time."
```

---

## 6. Removed Section

### Open Questions - REMOVED ENTIRELY
The entire "Open Questions" section with 5 questions has been removed and replaced with integrated decisions throughout the story.

---

## Impact Summary

| Section | Change Type | Impact |
|---------|-------------|--------|
| Business Rules | Major Addition | +3 new subsections with deterministic rules |
| Data Requirements | Major Expansion | +2 complete domain API contracts with schemas |
| Alternate/Error Flows | Significant Update | Specific timeouts and degradation behavior |
| Functional Behavior | Minor Update | Clarified option ordering |
| Acceptance Criteria | Addition | +2 new test scenarios |
| Open Questions | Removal | Section eliminated (all resolved) |

---

## Developer Impact

### Before Clarification Resolution
- ❌ 5 open questions blocking implementation
- ❌ Ambiguous option presentation order
- ❌ Unknown timeout thresholds
- ❌ Missing domain API contracts
- ❌ Unclear lead time sourcing

### After Clarification Resolution
- ✅ All questions resolved
- ✅ Deterministic option ordering
- ✅ Specific timeout thresholds (800ms, 1200ms)
- ✅ Complete API schemas for both domains
- ✅ Tiered lead time sourcing model
- ✅ 6 comprehensive test scenarios

---

**Result:** Story is now **implementation-ready** with no ambiguity or guesswork required.
