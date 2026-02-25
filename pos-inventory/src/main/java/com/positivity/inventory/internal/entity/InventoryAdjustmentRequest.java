package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

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

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
        if (status == null) {
            status = AdjustmentRequestStatus.PENDING;
        }
    }
}
