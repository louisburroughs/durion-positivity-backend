package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only per-product unit-of-measure row carried on {@code catalog.product.updated} facts
 * (ADR-0044 §6, ADR-0055, #1413). pos-catalog owns these rows; nothing in this module may write
 * the table except {@code CatalogEventsListener}.
 *
 * <p>What this module reads is the {@code BASE} row's {@code precisionScale}: the number of decimal
 * places the product's stock is divisible to. Zero — and equally, no row at all — means the product
 * is stocked and issued in whole units, which is what {@code PartQuantityDivisibilityService}
 * enforces on every part quantity.
 *
 * <p>{@code factorToBase} converts one unit of {@code uomCode} into the product's base UoM
 * ({@code 1 CASE = factorToBase × baseUom}); {@code 1} for {@code BASE} rows. It is replicated
 * because the fact carries it and the row would otherwise misrepresent what catalog published, not
 * because this module converts anything yet — the work-order line has no UoM column (#1415).
 *
 * <p><b>Freshness bound:</b> consistent with catalog's product row at emission time, arriving
 * within normal Kafka lag. A late arrival can only mean a newly-divisible product is briefly still
 * judged as integral, which blocks a fractional entry rather than admitting a wrong one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(ExtProductUomReplica.Key.class)
@Table(name = "ext_product_uom")
public class ExtProductUomReplica {

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Id
    @Column(name = "uom_code", nullable = false, length = 32)
    private String uomCode;

    @Column(name = "uom_type", length = 16)
    private String uomType;

    @Column(name = "factor_to_base", nullable = false, precision = 20, scale = 6)
    private BigDecimal factorToBase;

    @Column(name = "precision_scale", nullable = false)
    private int precisionScale;

    /**
     * Envelope {@code aggregateVersion} of the fact that wrote this row — catalog's product
     * {@code updatedAt} as epoch millis. Read only by the listener's stale guard.
     */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the product id IS a UUIDv7
     * minted by the owning module; this replica stores it verbatim rather than generating one.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }

    /** Composite key: the owner allows exactly one row per (product, uomCode). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID productId;
        private String uomCode;
    }
}
