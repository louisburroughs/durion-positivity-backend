package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One SKU line of a {@link TransferOrder} (odoo-parity C1, issue #1035).
 *
 * <p>Quantity invariants: {@code 0 <= receivedQty <= dispatchedQty <= requestedQty}. The
 * in-transit remainder of a line is {@code dispatchedQty - receivedQty} while the order is
 * DISPATCHED/PARTIALLY_RECEIVED (parity-C2).
 */
@Entity
@Table(name = "transfer_order_line")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class TransferOrderLine {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_order_id", nullable = false)
    private TransferOrder transferOrder;

    /** Position of the line within the order (1-based, stable ordering). */
    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    /** SKU / stock-item identifier (ledger stock-item string). */
    @Column(nullable = false)
    private String sku;

    /** Quantity requested for transfer; always positive. */
    @Column(name = "requested_qty", nullable = false)
    private Integer requestedQty;

    /** Quantity dispatched from the source so far. */
    @Builder.Default
    @Column(name = "dispatched_qty", nullable = false)
    private Integer dispatchedQty = 0;

    /** Quantity received at the destination so far. */
    @Builder.Default
    @Column(name = "received_qty", nullable = false)
    private Integer receivedQty = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
