package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only catalog product replica fed by {@code catalog.events.v1} (ADR-0044 §6, #924).
 *
 * <p>pos-catalog owns these facts; only the {@code CatalogEventsListener} consumer writes this
 * table. Serves candidate-line product lookup and eligibility (manufacturer / warranty terms)
 * previously answered by the retired synchronous {@code CatalogClient.getProduct}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_catalog")
public class ExtCatalogReplica {

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku", length = 128)
    private String sku;

    @Column(name = "name", length = 512)
    private String name;

    @Column(name = "manufacturer_id")
    private UUID manufacturerId;

    @Column(name = "manufacturer_name", length = 255)
    private String manufacturerName;

    @Column(name = "manufacturer_brand", length = 255)
    private String manufacturerBrand;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "warranty", length = 2048)
    private String warranty;

    @Column(name = "manufacturer_warranty", length = 2048)
    private String manufacturerWarranty;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hooks for the ArchUnit UUIDv7 rules (ADR-0013): the PK is a UUIDv7 copied
     * verbatim from the owning aggregate's event, so the replica generates no identifier of its own.
     * References both {@link UUIDv7Id} (module ArchitectureTest) and {@link UUIDv7Generator}
     * (cross-module EntityStandards) so both identifier-standard rules recognise the replica.
     */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
