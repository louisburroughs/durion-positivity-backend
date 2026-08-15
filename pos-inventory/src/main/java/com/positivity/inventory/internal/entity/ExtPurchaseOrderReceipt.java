package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the projection has actually applied for one order, so completeness is a query rather than an
 * inference.
 *
 * <p>A replica that feeds availability-to-promise cannot only be eventually correct — it has to be
 * able to say when it is behind. Holding the highest applied version per order means a gap between
 * what the owner published and what was applied can be asked about directly, instead of being
 * noticed as a quietly smaller supply figure.
 */
@Entity
@Table(name = "ext_purchase_order_receipt")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtPurchaseOrderReceipt {

    @Id
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    /** Highest {@code aggregateVersion} applied for this order. */
    @Column(name = "applied_version", nullable = false)
    private long appliedVersion;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim. Minting one locally
     * would break the correspondence with the order the owner published.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
