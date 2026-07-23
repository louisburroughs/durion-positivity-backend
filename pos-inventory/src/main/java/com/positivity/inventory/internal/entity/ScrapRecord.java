package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.ScrapCostSource;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Scrap/write-off document (odoo-parity D1, issue #1030; Odoo {@code stock.scrap} analog).
 *
 * <p>Tracks the lifecycle from proposal through value-threshold approval to the
 * {@code SCRAP_OUT} ledger posting. The posted ledger entry carries
 * {@code sourceTransactionId = scrapId} so reason analytics can join document and ledger.
 *
 * <p>{@code unitCostSnapshot} follows the interim cost rule (ADR-0048): latest receipt cost at
 * creation time, labeled by {@code costSource}; nullable when no cost history exists — the scrap
 * value is then unknown and approval is always required. {@code lotId} and {@code serialNumber}
 * are reserved columns wired by the WS-E stories.
 */
@Entity
@Table(
        name = "scrap_record",
        indexes = {
            @Index(name = "idx_scrap_record_status", columnList = "status"),
            @Index(name = "idx_scrap_record_stock_item", columnList = "stock_item_id"),
            @Index(name = "idx_scrap_record_location", columnList = "location_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ScrapRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID scrapId;

    /** SKU / stock-item identifier being scrapped (ledger stock-item string). */
    @Column(name = "stock_item_id", nullable = false)
    private String stockItemId;

    /** Quantity written off; always positive. */
    @Column(nullable = false)
    private Integer quantity;

    /** Location the stock is scrapped from. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** Bin-level storage location, when the scrap is bin-scoped. */
    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    /** Scrap reason taxonomy value; OTHER requires notes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private ScrapReasonCode reasonCode;

    /** Free-text notes; mandatory when reasonCode is OTHER. */
    @Column(length = 2000)
    private String notes;

    /** Workorder the scrapped part belonged to, when linked. */
    @Column(name = "workorder_id")
    private UUID workorderId;

    /** Lot linkage; reserved for WS-E lot tracking. */
    @Column(name = "lot_id")
    private UUID lotId;

    /** Serial-number linkage; reserved for WS-E serial tracking. */
    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    /** Photo/attachment reference (storage out of scope). */
    @Column(name = "attachment_reference", length = 500)
    private String attachmentReference;

    /** Unit cost snapshot at creation (interim rule: latest receipt cost); null when unknown. */
    @Column(name = "unit_cost_snapshot", precision = 19, scale = 4)
    private BigDecimal unitCostSnapshot;

    /** Origin of the cost snapshot. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_source", nullable = false, length = 30)
    private ScrapCostSource costSource;

    /** When true, posting triggers a pick-face replenishment evaluation (Odoo do_replenish). */
    @Column(name = "should_replenish", nullable = false)
    @Builder.Default
    private Boolean shouldReplenish = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScrapStatus status;

    /** Required approval tier; null when auto-approved. */
    @Enumerated(EnumType.STRING)
    @Column(name = "required_approval_tier", length = 30)
    private ApprovalTier requiredApprovalTier;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "rejected_by")
    private String rejectedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    /** SCRAP_OUT ledger entry created when posted. */
    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    /** Error message when status is FAILED. */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    /**
     * Absolute monetary value of the scrap, or null when the cost snapshot is
     * unknown (value cannot be evaluated against thresholds).
     */
    public BigDecimal getScrapValue() {
        if (unitCostSnapshot == null) {
            return null;
        }
        return unitCostSnapshot.multiply(BigDecimal.valueOf(quantity));
    }
}
