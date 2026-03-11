package com.positivity.workorder.internal.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ApprovalConfiguration {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        calculatePriority();
    }

    // Location ID - if null, applies to all locations
    private UUID locationId;

    // Customer ID - if null, applies to all customers at the location
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ApprovalMethod approvalMethod = ApprovalMethod.CLICK_CONFIRM;

    // Number of days a declined estimate can be reopened
    @Builder.Default
    private Integer declineExpiryDays = 30;

    // Number of days an estimate is valid for approval (null = no expiration)
    private Integer approvalWindowDays;

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
        CLICK_CONFIRM, // Service advisor clicks to confirm
        SIGNATURE, // Customer signature on tablet
        ELECTRONIC_SIGNATURE, // Electronic signature via email/SMS
        VERBAL_CONFIRMATION // Verbal confirmation recorded
    }

    @PreUpdate
    protected void preUpdate() {
        calculatePriority();
    }

    private void calculatePriority() {
        if (customerId != null) {
            priority = 2;
        } else if (locationId != null) {
            priority = 1;
        } else {
            priority = 0;
        }
    }

    public ApprovalConfiguration(UUID id) {
        this.id = id;
    }
}
