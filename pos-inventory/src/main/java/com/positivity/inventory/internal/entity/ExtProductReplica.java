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
 * Read-only catalog product replica fed by {@code catalog.product.updated} v2 facts on
 * {@code catalog.events.v1} (odoo-parity B1, #1033; contract X1, #1023).
 *
 * <p>pos-catalog owns these facts; nothing in this module may write the table except the event
 * consumer. Holds every v2 contract field in one place — base UoM + tracking level here,
 * substitution group id here with the member set in {@link ExtProductSubstitutionReplica}, and the
 * UoM conversion set in {@link ExtProductUomReplica} — so the E1 (tracking) and G2 (substitution)
 * stories consume this replica instead of adding further catalog listeners.
 *
 * <p>{@code aggregateVersion} carries the product's {@code updatedAt} epoch millis (catalog has no
 * JPA {@code @Version}); the consumer skips only strictly-lower versions — equal versions must
 * apply, because catalog fans out multiple facts in the same millisecond on group-membership
 * changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_product")
public class ExtProductReplica {

    /** Tracking level applied when the fact predates schema v2 ({@code trackingLevel} null). */
    public static final String TRACKING_LEVEL_NONE = "NONE";

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "base_uom", length = 32)
    private String baseUom;

    @Column(name = "tracking_level", nullable = false, length = 16)
    private String trackingLevel;

    @Column(name = "substitution_group_id")
    private UUID substitutionGroupId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
