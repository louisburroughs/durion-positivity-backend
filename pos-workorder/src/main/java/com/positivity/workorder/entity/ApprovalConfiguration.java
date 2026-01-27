package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalConfiguration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Location ID - if null, applies to all locations
    private Long locationId;

    // Customer ID - if null, applies to all customers at the location
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ApprovalMethod approvalMethod = ApprovalMethod.CLICK_CONFIRM;

    // Number of days a declined estimate can be reopened
    @Builder.Default
    private Integer declineExpiryDays = 30;

    // Whether signature is required (if approvalMethod is SIGNATURE)
    @Builder.Default
    private Boolean requireSignature = false;

    // Priority for configuration matching (higher = more specific)
    // 0 = default (no location, no customer)
    // 1 = location-specific
    // 2 = customer-specific
    @Builder.Default
    private Integer priority = 0;

    public enum ApprovalMethod {
        CLICK_CONFIRM,      // Service advisor clicks to confirm
        SIGNATURE,          // Customer signature on tablet
        ELECTRONIC_SIGNATURE, // Electronic signature via email/SMS
        VERBAL_CONFIRMATION // Verbal confirmation recorded
    }

    @PrePersist
    @PreUpdate
    protected void calculatePriority() {
        if (customerId != null) {
            priority = 2;
        } else if (locationId != null) {
            priority = 1;
        } else {
            priority = 0;
        }
    }
}
