package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only substitution-group membership replica carried on {@code catalog.product.updated} v2
 * facts (odoo-parity B1, #1033; consumed by G2). Every fact carries the full member set of the
 * product's group (including the product itself) — the consumer replaces the product's rows
 * wholesale; a fact without membership clears them (the product left its group).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(ExtProductSubstitutionReplica.Key.class)
@Table(name = "ext_product_substitution")
public class ExtProductSubstitutionReplica {

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Id
    @Column(name = "member_product_id", nullable = false)
    private UUID memberProductId;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): both ids ARE UUIDv7s
     * minted by the owning module; this replica stores them verbatim.
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
        private UUID memberProductId;
    }
}
