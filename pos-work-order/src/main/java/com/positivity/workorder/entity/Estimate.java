package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Estimate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId; // Reference to Shop
    private Long vehicleId; // Reference to Vehicle
    private Long customerId; // Reference to Customer

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EstimateStatus status = EstimateStatus.DRAFT;

    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime declinedAt;
    private LocalDateTime expiresAt;

    // Configuration reference for approval method
    private Long approvalConfigurationId;

    // Notes or reason for decline
    private String declineReason;

    // Approved by (service advisor or system user ID)
    private Long approvedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
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
}
