package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "inventory_reservation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ReservationEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID reservationId;

    /**
     * The workorder line this reservation fulfils, when demand came from a workorder. Exactly one
     * of this and {@link #salesOrderLineId} is set (CAP #1315 widened this table to accept either
     * demand source; DB CHECK enforces the invariant, a partial unique index per column enforces
     * one reservation per line either way).
     */
    @Column(name = "workorder_line_id")
    private UUID workorderLineId;

    /**
     * The sales-order line this reservation fulfils, when demand came from a sales order (CAP
     * #1315). Exactly one of this and {@link #workorderLineId} is set.
     */
    @Column(name = "sales_order_line_id")
    private UUID salesOrderLineId;

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    /**
     * Quantity demanded. Decimal-capable (ADR-0055, #1414) and gated by the referenced product's
     * declared {@code precision_scale}, not by this type. Compare with {@code compareTo}.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal requiredQuantity;

    /** Quantity hard-allocated against {@link #requiredQuantity}, on the same decimal basis. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal allocatedQuantity = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private int priority = 5;

    @Column(nullable = true)
    private Instant waitingSince;

    @Column(nullable = true)
    private Instant dueDateTime;

    @Column(nullable = true)
    private Instant scheduleStartTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "reservation", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<AllocationEntity> allocations = new ArrayList<>();

    /**
     * Whichever demand line this reservation is actually keyed on (CAP #1315). Fairness/priority
     * logic that only cares "which demand line" — not which kind — reads this instead of branching
     * on both fields.
     */
    @Transient
    public UUID getDemandLineId() {
        return workorderLineId != null ? workorderLineId : salesOrderLineId;
    }
}
