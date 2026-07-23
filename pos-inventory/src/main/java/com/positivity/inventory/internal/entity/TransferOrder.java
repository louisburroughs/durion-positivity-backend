package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.TransferOrderStatus;
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
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Cross-site transfer order aggregate (odoo-parity C1, issue #1035; Odoo internal-picking /
 * inter-warehouse resupply analog).
 *
 * <p>Moves stock between two different sites through an explicit document lifecycle
 * ({@link TransferOrderStatus}); intra-site bin moves keep using the immediate
 * {@code POST /stock-movements} TRANSFER pair. Ledger postings made on behalf of this order
 * (parity-C2 dispatch/receive) carry {@code sourceTransactionId = transferOrderId} so document
 * and ledger join without new columns.
 *
 * <p>{@code sourceStorageLocationId}/{@code destinationStorageLocationId} optionally pin the
 * bin-level posting location at each end; when absent, postings land on the site-level key.
 */
@Entity
@Table(
        name = "transfer_order",
        indexes = {
            @Index(name = "idx_transfer_order_status", columnList = "status"),
            @Index(name = "idx_transfer_order_source", columnList = "source_location_id"),
            @Index(name = "idx_transfer_order_destination", columnList = "destination_location_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class TransferOrder {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID transferOrderId;

    /** Source site the transfer ships from (LocationRef site id). */
    @Column(name = "source_location_id", nullable = false)
    private UUID sourceLocationId;

    /** Optional bin-level source storage location under the source site. */
    @Column(name = "source_storage_location_id")
    private UUID sourceStorageLocationId;

    /** Destination site the transfer ships to (LocationRef site id). */
    @Column(name = "destination_location_id", nullable = false)
    private UUID destinationLocationId;

    /** Optional bin-level destination storage location under the destination site. */
    @Column(name = "destination_storage_location_id")
    private UUID destinationStorageLocationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransferOrderStatus status;

    @Column(length = 2000)
    private String notes;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "dispatched_by")
    private String dispatchedBy;

    @Builder.Default
    @OneToMany(mappedBy = "transferOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    private List<TransferOrderLine> lines = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Adds a line and maintains both sides of the association. */
    public void addLine(TransferOrderLine line) {
        line.setTransferOrder(this);
        lines.add(line);
    }
}
