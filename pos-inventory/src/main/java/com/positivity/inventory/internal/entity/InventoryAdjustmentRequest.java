package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Pending adjustment request awaiting approval before being posted to the
 * ledger.
 *
 * <p>
 * Created by users with INVENTORY_ADJUST_CREATE permission.
 * Approved (and posted to ledger) by users with INVENTORY_ADJUST_APPROVE
 * permission.
 *
 * Issue: CAP-215 Story #37
 */
@Entity
@Table(name = "inventory_adjustment_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryAdjustmentRequest {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID adjustmentRequestId;

    @NonNull
    @Column(nullable = false)
    private String productSku;

    @NonNull
    @Column(nullable = false)
    private UUID locationId;

    @Column(nullable = false)
    private Integer quantity;

    @NonNull
    @Column(nullable = false, length = 100)
    private String reasonCode;

    @Column(length = 50)
    private String unitOfMeasure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdjustmentRequestStatus status;

    @Column(nullable = false)
    private String requestedByUserId;

    @Column
    private String approvedByUserId;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    @Column
    private Instant approvedAt;

    /**
     * Timestamp when this configuration was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when this configuration was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = AdjustmentRequestStatus.PENDING;
        }
    }
}
