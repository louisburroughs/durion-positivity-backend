package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Represents a line item (part or labor) on an estimate.
 * Each item captures quantity, pricing, and optional references to products or
 * services.
 */
@Entity
@Table(name = "estimate_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class EstimateItem {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "estimate_id", nullable = false)
    @ToString.Exclude
    private Estimate estimate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstimateItemType itemType; // PART or LABOR

    @Column(length = 500)
    @Nullable
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /**
     * The unit {@code quantity} is expressed in (ADR-0055 stage 3, #1415). {@code null} means the
     * product's base unit — today's implicit assumption, unchanged; every row that predates this
     * column reads as {@code null} with no behavior change.
     *
     * <p><b>LABOR rows always carry {@code null}.</b> {@code quantity} is shared between PART and
     * LABOR, but hours are not a product unit of measure and have no catalog conversion row to
     * convert from — {@code uomCode} names a {@code product_uom} entry, and a LABOR row names no
     * product. A non-null {@code uomCode} on a LABOR row is rejected at entry (400).
     */
    @Column(name = "uom_code", length = 32)
    @Nullable
    private String uomCode;

    // For PART items: unit price; For LABOR items: labor rate (hourly)
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    // Line total = quantity * unitPrice (calculated field)
    @Column(precision = 19, scale = 2)
    private BigDecimal lineTotal;

    @Column(length = 50)
    @Nullable
    private String taxCode; // Optional tax code for line item

    // Optional references to catalog items
    @Column(columnDefinition = "UUID")
    @Nullable
    private UUID productId; // Reference to Product entity (for PART items)

    @Column(columnDefinition = "UUID")
    @Nullable
    private UUID serviceId; // Reference to Service entity (for LABOR items)

    // Guide-time snapshot (#1569, sourcing plan §6.3): the book-time baseline the labor guide
    // published when this LABOR line was created, with its provenance. quantity stays the
    // AGREED hours; these record what the guide said, so an adjusted quote keeps both numbers.
    @Column(name = "guide_hours", precision = 5, scale = 1)
    @Nullable
    private BigDecimal guideHours;

    @Column(name = "guide_source_code", length = 32)
    @Nullable
    private String guideSourceCode;

    @Column(name = "guide_source_revision", length = 64)
    @Nullable
    private String guideSourceRevision;

    @Column(name = "guide_match_grade", length = 24)
    @Nullable
    private String guideMatchGrade;

    @Column(name = "guide_overlap_group", length = 64)
    @Nullable
    private String guideOverlapGroup;

    // Comma-separated Durion operation codes whose time this line's guide hours already
    // include; read back whole for the overlap-aware summation, never queried relationally.
    @Column(name = "guide_included_op_codes")
    @Nullable
    private String guideIncludedOpCodes;

    // Labor-rate snapshot (#1575 Tier 0 / #1569 R4): where the price on this LABOR line came
    // from. The mirror of the guide-time block above — unitPrice stays the CHARGED rate, these
    // record what pos-price published and which matrix steps produced it, so an adjusted quote
    // keeps both numbers the way it already keeps guide hours beside agreed hours.
    @Column(name = "rate_hourly", precision = 10, scale = 4)
    @Nullable
    private BigDecimal rateHourly;

    @Column(name = "rate_base_hourly", precision = 10, scale = 4)
    @Nullable
    private BigDecimal rateBaseHourly;

    @Column(name = "rate_currency", length = 3)
    @Nullable
    private String rateCurrency;

    @Column(name = "rate_scope", length = 24)
    @Nullable
    private String rateScope;

    @Column(name = "rate_id")
    @Nullable
    private UUID rateId;

    // Comma-separated labor-matrix codes that actually applied; read back whole to show the
    // derivation, never queried relationally.
    @Column(name = "rate_adjustment_codes")
    @Nullable
    private String rateAdjustmentCodes;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private String createdById; // User who created this item

    // Soft delete flag
    @Builder.Default
    @Column(nullable = false)
    private Boolean deleted = false;

    // CAP:003 - Selective line item approval fields
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING_APPROVAL;

    @Column
    private LocalDateTime approvalTimestamp;

    @Column(length = 100)
    @Nullable
    private String approvalMethodUsed; // e.g., "DigitalSignature", "ServiceAdvisorElectronic"

    @Column(columnDefinition = "UUID")
    @Nullable
    private UUID approvalProofId; // Reference to signature data or confirmation record

    @Column(length = 1000)
    @Nullable
    private String rejectionReason; // Required when approvalStatus = DECLINED

    @Column(length = 1000)
    @Nullable
    private String approvalNotes; // Additional notes about approval/rejection decision

    @PrePersist
    protected void prePersist() {
        calculateLineTotal();
    }

    @PreUpdate
    protected void onUpdate() {
        calculateLineTotal();
    }

    /**
     * Calculate the line total based on quantity and unit price.
     */
    public void calculateLineTotal() {
        if (quantity != null && unitPrice != null) {
            lineTotal = quantity.multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP);
        } else {
            lineTotal = null;
        }
    }

    /**
     * Validate item based on type.
     */
    public void validate() {
        if (itemType == EstimateItemType.PART) {
            if (productId == null && (description == null || description.isBlank())) {
                throw new WorkorderRequestValidationException("PART items must have either productId or description");
            }
        } else if (itemType == EstimateItemType.LABOR) {
            if (serviceId == null && (description == null || description.isBlank())) {
                throw new WorkorderRequestValidationException("LABOR items must have either serviceId or description");
            }
        }
    }

    public EstimateItem(UUID id) {
        this.id = id;
    }

    @Transient
    public UUID getEstimateId() {
        return estimate != null ? estimate.getId() : null;
    }

    @Transient
    public void setEstimateId(UUID estimateId) {
        this.estimate = new Estimate(estimateId);
    }
}
