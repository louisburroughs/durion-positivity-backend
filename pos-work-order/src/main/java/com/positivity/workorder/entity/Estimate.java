package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "estimate", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"locationId", "estimateNumber"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Estimate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = false) // Uniqueness enforced at DB level with composite constraint
    private String estimateNumber; // Human-readable identifier (e.g., EST-2024-1001)
    
    private Long locationId; // Reference to Location (previously shopId)
    private Long vehicleId; // Reference to Vehicle
    private Long customerId; // Reference to Customer

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EstimateStatus status = EstimateStatus.DRAFT;

    private String currencyUomId; // Currency code (e.g., 'USD')
    private Long taxRegionId; // Reference to tax region
    
    private Long createdByUserId; // User who created the estimate
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime declinedAt;
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private Long createdById; // User who created the estimate

    // Configuration reference for approval method
    private Long approvalConfigurationId;

    // Notes or reason for decline
    private String declineReason;

    // Approved by (service advisor or system user ID)
    private Long approvedBy;
    
    // Financial tracking for approval invalidation
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal total;
    
    // Version tracking for estimate revisions
    @Builder.Default
    private Integer version = 1;
    
    // Legacy field for backward compatibility
    @Deprecated
    @Transient
    private Long shopId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum EstimateStatus {
        DRAFT,
        APPROVED,
        DECLINED,
        EXPIRED
    }

    /**
     * Check if estimate can be transitioned to approved state
     */
    public boolean canApprove() {
        return status == EstimateStatus.DRAFT;
    }

    /**
     * Check if estimate can be transitioned to declined state
     */
    public boolean canDecline() {
        return status == EstimateStatus.DRAFT || status == EstimateStatus.APPROVED;
    }

    /**
     * Check if estimate can be reopened (transitioned from declined to draft)
     */
    public boolean canReopen(int configuredExpiryDays) {
        if (status != EstimateStatus.DECLINED || declinedAt == null) {
            return false;
        }
        LocalDateTime expiryDate = declinedAt.plusDays(configuredExpiryDays);
        return LocalDateTime.now().isBefore(expiryDate);
    }
    
    /**
     * Backward compatibility - shopId is now locationId
     */
    @Deprecated
    public Long getShopId() {
        return locationId;
    }
    
    /**
     * Backward compatibility - shopId is now locationId
     */
    @Deprecated
    public void setShopId(Long shopId) {
        this.locationId = shopId;
    }
}
