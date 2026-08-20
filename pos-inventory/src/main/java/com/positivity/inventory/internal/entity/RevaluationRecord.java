package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.RevaluationStatus;
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
 * Manual cost-revaluation document and audit log (odoo-parity J4, issue #1054; Odoo
 * {@code product.value} analog). This single append-only row is both the approval-workflow document
 * and the who/when/old/new audit record (ADR-0024) that governs a standard-price / AVCO correction —
 * mirroring the costing-audit pattern of {@link CostMethodChangeLog} and the workflow lifecycle of
 * {@link ScrapRecord}.
 *
 * <p>The revaluation targets the SKU's resolved {@link CostingMethod}: STANDARD restates
 * {@code sku_cost_state.standard_cost}, AVERAGE restates {@code sku_cost_state.avg_cost}.
 * {@code previousUnitCost} is the cost of that method before the change (null when the SKU was
 * uncosted); {@code newUnitCost} the corrected value; {@code valueDelta} the signed inventory value
 * change {@code (newUnitCost − previousUnitCost) × onHandQuantity}. The value delta gates the
 * approval tier via the {@code REVALUATION} rows of {@code approval_threshold_config}.
 */
@Entity
@Table(
        name = "revaluation_record",
        indexes = {
            @Index(name = "idx_revaluation_record_status", columnList = "status"),
            @Index(name = "idx_revaluation_record_stock_item", columnList = "stock_item_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class RevaluationRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "revaluation_id", updatable = false, nullable = false)
    private UUID revaluationId;

    /** SKU / stock-item identifier being revalued (ledger stock-item string). */
    @Column(name = "stock_item_id", nullable = false, length = 255)
    private String stockItemId;

    /** Resolved costing method whose cost this revaluation corrects (STANDARD or AVERAGE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "costing_method", nullable = false, length = 32)
    private CostingMethod costingMethod;

    /** Unit cost before the revaluation; null when the SKU carried no prior cost of the method. */
    @Column(name = "previous_unit_cost", precision = 19, scale = 6)
    private BigDecimal previousUnitCost;

    /** Corrected unit cost applied on approval. */
    @Column(name = "new_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal newUnitCost;

    /** On-hand quantity the engine had costed at revaluation time (delta basis). */
    @Column(name = "on_hand_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandQuantity;

    /** Signed inventory value change: (newUnitCost − previousUnitCost) × onHandQuantity. */
    @Column(name = "value_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal valueDelta;

    /** Operator-supplied justification for the correction. */
    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RevaluationStatus status;

    /** Required approval tier; null when auto-applied. */
    @Enumerated(EnumType.STRING)
    @Column(name = "required_approval_tier", length = 30)
    private ApprovalTier requiredApprovalTier;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "rejected_by", length = 255)
    private String rejectedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When the revaluation was applied to the cost state (auto or on approval). */
    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;
}
