package com.positivity.workorder.internal.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID estimateId; // Foreign key to Estimate

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstimateItemType itemType; // PART or LABOR

    @Column(length = 500)
    @Nullable
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

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
                throw new IllegalArgumentException("PART items must have either productId or description");
            }
        } else if (itemType == EstimateItemType.LABOR) {
            if (serviceId == null && (description == null || description.isBlank())) {
                throw new IllegalArgumentException("LABOR items must have either serviceId or description");
            }
        }
    }
}
