package com.positivity.inventory.internal.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.shared.id.UUIDv7Generator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Configuration for approval thresholds used to evaluate cycle count
 * adjustments.
 * 
 * <p>
 * Uses a composite threshold model: approval is required if ANY threshold is
 * exceeded.
 * Supports two-tier approval based on threshold values.
 */
@Entity
@Table(name = "approval_threshold_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalThresholdConfig {

    @Id
    private UUID configId;

    /**
     * Approval tier this configuration applies to.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private ApprovalTier approvalTier;

    /**
     * Absolute unit variance threshold.
     * Example: 3 units
     * If |quantityChange| &gt;= this value, approval is required.
     */
    @Column(nullable = false)
    private Integer unitVarianceThreshold;

    /**
     * Total monetary value variance threshold.
     * Example: $100.00
     * If |quantityChange * unitCost| &gt;= this value, approval is required.
     */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal valueVarianceThreshold;

    /**
     * Percentage variance threshold (0-100).
     * Example: 5.0 (meaning 5%)
     * If |quantityChange| / onHandQty &gt;= this percentage, approval is required.
     */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentageVarianceThreshold;

    /**
     * Whether this configuration is currently active.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Timestamp when this configuration was created.
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when this configuration was last updated.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (configId == null) {
            configId = UUIDv7Generator.generate();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
