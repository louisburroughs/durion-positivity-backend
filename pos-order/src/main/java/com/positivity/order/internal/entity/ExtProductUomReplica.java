package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
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
 * Read-only per-product UoM conversion row carried on {@code catalog.product.updated} facts
 * (CAP-320 #1334, ADR-0044 R3). Every fact carries the product's full conversion set, so the
 * consumer replaces the product's rows wholesale. The owner allows exactly one row per
 * (product, uomCode), so that pair is the natural key.
 *
 * <p>{@code factorToBase} converts one unit of {@code uomCode} into the product's base UoM
 * ({@code 1 CASE = factorToBase × baseUom}); {@code 1} for {@code BASE} rows.
 * {@code precisionScale} is the decimal scale for quantities expressed in this UoM.
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
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the product id IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID productId;
        private String uomCode;
    }
}
