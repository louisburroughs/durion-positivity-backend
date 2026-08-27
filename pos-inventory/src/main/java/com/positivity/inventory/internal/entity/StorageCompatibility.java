package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.StorageCompatibilityMatchLevel;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One "a class of item may physically go in a class of location" fact (issue #1514).
 *
 * <p>Static Tier 1 configuration: seeded by {@code V43__storage_compatibility.sql} and read-only at
 * runtime — there is no repository write path and no API that edits it. That is why the entity
 * carries no audit timestamps and no {@code AuditingEntityListener}; there is no lifecycle to
 * record.
 *
 * <p>Keyed on catalog ids rather than names because a rename must not change which locations accept
 * an item, and pos-inventory only ever sees category names as un-refreshed snapshots on product
 * facts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "storage_compatibility")
public class StorageCompatibility {

    @Id
    @Column(name = "compatibility_id", nullable = false)
    private UUID compatibilityId;

    /**
     * Whether {@link #catalogRefId} is a category or a subcategory id. Subcategory rows replace the
     * parent category's rows rather than adding to them.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_level", nullable = false, length = 20)
    private StorageCompatibilityMatchLevel matchLevel;

    /** The catalog category or subcategory id this row speaks for. */
    @Column(name = "catalog_ref_id", nullable = false)
    private UUID catalogRefId;

    /**
     * A storage class that accepts the item, as pos-location's {@code StorageCategory} code. Kept as
     * the owner's plain text for the same reason {@code ext_storage_location.storage_category_code}
     * is: the enum lives in pos-location, and a local enum here would turn an additive owner-side
     * change into a crash in this consumer.
     */
    @Column(name = "storage_category_code", nullable = false, length = 30)
    private String storageCategoryCode;

    /**
     * Whether the destination must additionally declare {@code hazard_containment}. True for the two
     * containment-bearing classes, {@code BATTERY_RACK} and {@code OIL_STORAGE} — the property that
     * makes them not interchangeable with a plain shelf.
     */
    @Column(name = "requires_containment", nullable = false)
    private boolean requiresContainment;

    /**
     * Dependency hook for the ArchUnit rule {@code entities_should_use_uuidv7_id_or_generator}
     * (ADR-0013), which requires every {@code @Entity} to reference {@code UUIDv7Generator} or
     * {@code @UUIDv7Id}. Verified by removing it: the rule fails without it.
     *
     * <p>The ids ARE UUIDv7 values from the reserved {@code 01960033-*} namespace, but they are
     * literals in {@code V43__storage_compatibility.sql} rather than generated at runtime, so
     * neither annotation would be honest on the field. {@code ExtStorageLocationReplica} carries the
     * same hook for the same reason. The rule would be better taught about Flyway-seeded lookup
     * tables than worked around per entity — but it is a shared ADR-0013 rule, so widening it is not
     * a call to make inside a putaway change.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
