package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A product's identity code as catalog states it (CAP-320 #1330, ADR-0044 R3).
 *
 * <p>This is what lets a purchase-order line name an article the vendor recognises. Purchase-order
 * lines carry our own product ids, which mean nothing to a supplier; the EAN is the shared name.
 *
 * <p>A cleared code is stored as a null {@code code} rather than by removing the row, because
 * "this product carries no code" and "this module has never heard of this product" are different
 * answers and send a buyer to different places — the first is a gap in catalog to be filled, the
 * second a replica that has not caught up and will fix itself.
 */
@Entity
@Table(
        name = "ext_product_code",
        indexes = @Index(name = "idx_ext_product_code_lookup", columnList = "code_type, code"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtProductCode {

    /** pos-catalog's product id, replicated verbatim; never generated here. */
    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** e.g. {@code EAN}. Null when the product carries no code. */
    @Column(name = "code_type", length = 16)
    private String codeType;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the product id IS a
     * UUIDv7 minted by pos-catalog; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
