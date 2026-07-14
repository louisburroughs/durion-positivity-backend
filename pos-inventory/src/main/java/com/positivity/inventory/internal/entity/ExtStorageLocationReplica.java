package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
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
 * Read-only storage-location replica fed by {@code location.storage-location.updated} facts on
 * {@code location.events.v1} (ADR-0044 §6, #892). pos-location owns these facts; nothing in this
 * module may write the table except the event consumer.
 *
 * <p>Serves the site topology, descendant, and putaway/reservation validation queries previously
 * answered by the retired {@code StorageLocationTopologyClient} and
 * {@code StorageLocationValidationClient}. Storage locations are never hard-deleted by the owner
 * — decommissioning arrives as a status change on the same fact.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_storage_location")
public class ExtStorageLocationReplica {

    @Id
    @Column(name = "storage_location_id", nullable = false)
    private UUID storageLocationId;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "name")
    private String name;

    @Column(name = "barcode")
    private String barcode;

    @Column(name = "type", length = 64)
    private String type;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "parent_storage_location_id")
    private UUID parentStorageLocationId;

    @Column(name = "max_unit_capacity")
    private Integer maxUnitCapacity;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
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
