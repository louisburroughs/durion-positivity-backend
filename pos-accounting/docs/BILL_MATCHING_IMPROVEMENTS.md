# Vendor Bill Matching - Improvements & Roadmap

## ✅ Implemented (Current)

### Multi-Criteria Scoring Algorithm

- **Amount matching** (40 points): Invoice total within 10% of bill total
- **Date proximity** (20 points): Invoice date within 30 days of receipt date  
- **Partial line item scoring** (15 points): Placeholder for future SKU matching
- **Minimum threshold**: 50 points required for match
- **Audit logging**: All candidate scores logged for troubleshooting

## 🎯 Critical Next Steps

### 1. **Add Purchase Order Reference Matching** (Priority: HIGH)

**Schema Changes Needed:**

```sql
-- Add to vendor_bill table
ALTER TABLE vendor_bill 
ADD COLUMN purchase_order_id UUID,
ADD COLUMN purchase_order_number VARCHAR(50);

-- Add index for PO lookups
CREATE INDEX idx_vendor_bill_po ON vendor_bill(purchase_order_id);
```

**Scoring Impact:** +10 points for exact PO match

**Benefits:**

- Eliminates 90% of ambiguous matches
- Enables cross-validation with procurement system
- Required for proper three-way matching (PO → Receipt → Invoice)

---

### 2. **Implement Line Item Persistence & Matching** (Priority: HIGH)

**Schema Changes:**

```sql
CREATE TABLE vendor_bill_line (
    line_id UUID PRIMARY KEY,
    vendor_bill_id UUID NOT NULL REFERENCES vendor_bill(vendor_bill_id),
    line_number INTEGER NOT NULL,
    sku VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    quantity DECIMAL(19,4) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    line_total DECIMAL(19,4) NOT NULL,
    UNIQUE(vendor_bill_id, line_number)
);

CREATE INDEX idx_vendor_bill_line_bill ON vendor_bill_line(vendor_bill_id);
CREATE INDEX idx_vendor_bill_line_sku ON vendor_bill_line(sku);
```

**Matching Algorithm:**

```java
/**
 * Calculate line item overlap score using Jaccard similarity.
 * Score = (matching SKUs) / (total unique SKUs across both documents)
 * 
 * @return similarity score 0.0 to 1.0
 */
private double calculateLineItemSimilarity(
        List<VendorBillLine> billLines, 
        List<InvoiceLineItem> invoiceLines) {
    
    Set<String> billSkus = billLines.stream()
        .map(VendorBillLine::getSku)
        .collect(Collectors.toSet());
    
    Set<String> invoiceSkus = invoiceLines.stream()
        .map(InvoiceLineItem::getSku)
        .collect(Collectors.toSet());
    
    Set<String> intersection = new HashSet<>(billSkus);
    intersection.retainAll(invoiceSkus);
    
    Set<String> union = new HashSet<>(billSkus);
    union.addAll(invoiceSkus);
    
    return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
}
```

**Scoring:** 30 points × similarity score (0-30 points)

---

### 3. **Add Vendor-Specific Invoice Reference Tracking** (Priority: MEDIUM)

**Purpose:** Some vendors send invoice references in advance shipment notices or email notifications.

**Schema Change:**

```sql
ALTER TABLE vendor_bill 
ADD COLUMN expected_invoice_reference VARCHAR(100);

CREATE INDEX idx_vendor_bill_invoice_ref 
ON vendor_bill(expected_invoice_reference) 
WHERE expected_invoice_reference IS NOT NULL;
```

**Scoring:** +10 points for exact match (replaces PO score when PO not available)

---

### 4. **Handle Multiple Candidate Matches** (Priority: HIGH)

**Current Limitation:** Returns single best match or fails entirely.

**Improvement:** Support ambiguous match workflows:

```java
public enum MatchConfidence {
    HIGH_CONFIDENCE,    // Single candidate > 70 points
    MEDIUM_CONFIDENCE,  // Single candidate 50-70 points
    AMBIGUOUS,          // Multiple candidates > 50 points
    NO_MATCH            // No candidates > 50 points
}

/**
 * Enhanced return type with match confidence and alternatives.
 */
public class BillMatchResult {
    VendorBill bestMatch;
    MatchConfidence confidence;
    List<VendorBill> alternativeCandidates; // When AMBIGUOUS
    String matchingDetails; // Audit trail
}
```

**Status Transitions:**

- `HIGH_CONFIDENCE` → Auto-approve (current behavior)
- `MEDIUM_CONFIDENCE` → Require human review
- `AMBIGUOUS` → Create approval task with all candidates
- `NO_MATCH` → Create exception for procurement team

---

### 5. **Add Machine Learning for Historical Pattern Recognition** (Priority: LOW)

After collecting 6+ months of match data:

**Features to Extract:**

- Vendor-specific delivery lag (receipt to invoice)
- Common SKU substitutions by vendor
- Vendor invoice numbering patterns
- Seasonal ordering patterns

**Model:** Random Forest Classifier or Gradient Boosting

**Training Data:**

- Positive samples: Approved matches with scores
- Negative samples: Rejected/voided matches

---

## 🔧 Code Quality Improvements

### 6. **Extract Scoring Logic to Strategy Pattern**

```java
public interface BillMatchingStrategy {
    int calculateScore(VendorBill bill, VendorInvoiceReceivedEvent invoice);
    String getStrategyName();
    int getMaxPoints();
}

@Component
public class AmountMatchingStrategy implements BillMatchingStrategy {
    @Override
    public int calculateScore(VendorBill bill, VendorInvoiceReceivedEvent invoice) {
        // Existing amount matching logic
    }
}

// Strategies: AmountMatchingStrategy, DateProximityStrategy, 
//             PoReferenceStrategy, LineItemSimilarityStrategy

@Service
public class CompositeBillMatcher {
    private final List<BillMatchingStrategy> strategies;
    
    public int calculateTotalScore(VendorBill bill, VendorInvoiceReceivedEvent invoice) {
        return strategies.stream()
            .mapToInt(strategy -> strategy.calculateScore(bill, invoice))
            .sum();
    }
}
```

---

### 7. **Add Match Decision Audit Trail**

```sql
CREATE TABLE bill_match_audit (
    audit_id UUID PRIMARY KEY,
    invoice_event_id UUID NOT NULL,
    vendor_bill_id UUID,
    match_timestamp TIMESTAMP NOT NULL,
    match_confidence VARCHAR(20) NOT NULL,
    total_score INTEGER NOT NULL,
    score_breakdown JSONB NOT NULL, -- {"amount": 40, "date": 20, ...}
    decision VARCHAR(20) NOT NULL, -- MATCHED, REJECTED, MANUAL_REVIEW
    decision_reason TEXT,
    decided_by VARCHAR(50) -- System or operator ID
);
```

**Benefits:**

- Enables scoring algorithm tuning
- Provides evidence trail for financial audits
- Supports ML training data collection
- Helps identify vendor-specific matching issues

---

### 8. **Add Configuration for Scoring Weights**

```yaml
# application.yml
vendor-bill-matching:
  scoring:
    amount-weight: 40
    line-item-weight: 30
    date-proximity-weight: 20
    po-reference-weight: 10
    min-score-threshold: 50
  tolerances:
    amount-percent: 0.10  # 10%
    date-days: 30
  auto-approve:
    min-confidence-score: 70
```

**Benefits:**

- Tune scoring per environment (dev/staging/prod)
- Adjust for vendor-specific needs
- A/B test different scoring strategies

---

## 📊 Monitoring & Alerting

### Key Metrics to Track

1. **Match rate**: % of invoices auto-matched successfully
2. **Match confidence distribution**: HIGH/MEDIUM/AMBIGUOUS/NO_MATCH
3. **Average time-to-match**: Receipt to invoice processing time
4. **Exception rate**: % requiring manual intervention
5. **Score distribution**: Average scores by strategy component

### Alerts to Configure

- Match rate drops below 80% (possible vendor pattern change)
- > 10 NO_MATCH in 1 hour (integration issue?)
- > 5 AMBIGUOUS in 1 hour (duplicate data entry?)
- Average match confidence < 60 (scoring needs tuning)

---

## 🧪 Testing Strategy

### Unit Tests Needed

1. ✅ **Exact match**: Bill and invoice identical → 100% score
2. ✅ **Amount mismatch**: Bill $1000, Invoice $500 → Fail amount criteria
3. ✅ **Date proximity**: Same day vs. 60 days apart
4. ✅ **No candidates**: Empty pending bills
5. ✅ **Multiple candidates**: Select highest score
6. ✅ **Below threshold**: No candidate > 50 points → Return empty

### Integration Tests Needed

1. **End-to-end happy path**: GoodsReceived → Invoice → Auto-approve
2. **Ambiguous match**: Multiple pending bills for same vendor
3. **Out-of-order**: Invoice arrives before goods
4. **Partial delivery**: Multiple receipts, one invoice

---

## 📝 Documentation Updates Needed

1. **Operations Runbook**:
   - How to investigate failed matches
   - How to manually resolve ambiguous matches
   - How to adjust scoring configuration

2. **API Documentation**:
   - Update VendorInvoiceReceivedEvent schema to show matching criteria
   - Document match confidence levels

3. **Business Rules**:
   - Update `BACKEND_CONTRACT_GUIDE.md` with matching algorithm
   - Document when auto-approval occurs vs. manual review

---

## 🚀 Implementation Priority

| Priority | Task | Effort | Impact | Dependencies |
|----------|------|--------|--------|--------------|
| 🔴 P0 | Add PO reference matching | 2 days | High | Schema migration |
| 🔴 P0 | Persist line items | 3 days | High | Schema migration |
| 🟡 P1 | Handle ambiguous matches | 2 days | Medium | None |
| 🟡 P1 | Add match audit trail | 1 day | Medium | Schema migration |
| 🟢 P2 | Extract to Strategy pattern | 2 days | Low | None |
| 🟢 P2 | Add configuration | 1 day | Low | None |
| 🔵 P3 | ML pattern recognition | 2 weeks | Low | 6+ months data |

---

## 💡 Quick Wins

1. **Reduce amount tolerance** from 10% to 5% after collecting match statistics
2. **Increase minimum score** from 50 to 60 once PO matching is implemented
3. **Add vendor whitelist** for trusted vendors (auto-approve at lower thresholds)
4. **Implement timeout**: Flag bills pending match > 7 days for investigation

---

## 📖 References

- Issue #130: Vendor Bill Lifecycle (original requirement)
- Issue #278: Accounting Event Processing (this capability)
- `domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md`
- Three-Way Matching Best Practices: [Industry Standard Guide](#)
