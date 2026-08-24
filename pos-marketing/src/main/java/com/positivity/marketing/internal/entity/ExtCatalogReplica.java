package com.positivity.marketing.internal.entity;

import com.positivity.marketing.internal.enums.CatalogItemKind;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Read-only catalog replica fed by {@code catalog.events.v1} (ADR-0044 §6, #1306).
 *
 * <p>pos-catalog owns these facts; only {@code CatalogEventsListener} writes this table. It exists
 * for one question — does the thing a campaign's {@code catalogFocusRef} names actually exist, and
 * is it still active — so it holds only what answering that needs: the identifiers a reference may
 * be written with, and whether the item is still in the catalog.
 *
 * <p>{@code active} is false for a product pos-catalog has deactivated and for a service it has
 * deleted; the row is kept either way, because "this was removed" is a better answer to give a
 * marketer than silence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_catalog")
public class ExtCatalogReplica {

    /** The owning aggregate's id: a product id or a service id, never one this module minted. */
    @Id
    @Column(name = "catalog_item_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID catalogItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_kind", nullable = false, length = 16)
    private CatalogItemKind itemKind;

    @Column(name = "name", length = 512)
    private String name;

    /** Products only; the {@code sku:} form of a reference resolves against this. */
    @Column(name = "sku", length = 128)
    private String sku;

    /** Products only; a category is known here only through the products that carry it. */
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "active", nullable = false)
    private boolean active;

    /**
     * The owner's monotonic version for this item — {@code updatedAt} epoch millis, since catalog
     * entities carry no JPA {@code @Version}. Guards against an out-of-order redelivery undoing a
     * newer fact.
     */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Explicit dependency hooks for the ArchUnit UUIDv7 rules; the id is the owner's key. */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
