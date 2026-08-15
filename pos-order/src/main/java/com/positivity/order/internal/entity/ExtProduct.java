package com.positivity.order.internal.entity;

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
 * Read-only product replica (ADR-0044 §6, parity story B1): fed by {@code catalog.events.v1},
 * resolves SKU → productId/description/tracking level for pricing without synchronous REST toward
 * pos-catalog (a domain module).
 */
@Entity
@Table(name = "ext_product")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtProduct {

    @Id
    @Column(name = "product_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column
    private String sku;

    @Column
    private String name;

    @Column(nullable = false)
    private boolean active;

    /**
     * The product's base unit of measure — the unit every converted quantity ends up in.
     *
     * <p>Needed to recognise the identity case: a purchase-order line keyed in the product's own
     * base UoM has no conversion row of its own, and without this it would be indistinguishable
     * from a UoM catalog has never published.
     */
    @Column(name = "base_uom", length = 32)
    private String baseUom;

    /** Catalog {@code ProductTrackingLevel} name: NONE, LOT, SERIAL (consumed by story H3). */
    @Column(length = 32)
    private String trackingLevel;

    @Column(nullable = false)
    private long aggregateVersion;

    @Column(nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: productId is a UUIDv7 issued by pos-catalog. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
