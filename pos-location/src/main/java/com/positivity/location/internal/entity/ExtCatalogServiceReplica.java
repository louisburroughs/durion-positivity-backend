package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
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
 * Read-only catalog-service replica fed by {@code catalog.events.v1} (ADR-0044 §6). pos-catalog
 * owns these facts; nothing in this module may write the table except the event consumer.
 *
 * <p>Exists so a bay's specialty claim can be validated against the catalog vocabulary without a
 * synchronous cross-module read. Under CAP-325 a bay declares the catalog {@code operationCode}s
 * it is the only bay type able to perform, so {@code operation_code} here is the column the
 * specialty map joins against — which is why it is indexed and why {@code name} is carried at all
 * (a rejected code should be reportable by the name a human recognises).
 *
 * <p>Only what that validation needs is replicated. pos-workorder takes {@code operationCategory}
 * and {@code defaultLaborHours} from the same fact for its labor-hours summation; this module has
 * no use for either, and replicating data nothing reads is how replicas rot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_catalog_service")
public class ExtCatalogServiceReplica extends TenantScopedEntity {

    @Id
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "name")
    private String name;

    /**
     * The Durion operation identity, e.g. {@code BRAKE-PAD-REPLACE-FRONT} (ADR-0059 §3). Nullable
     * because pos-catalog's own column is: a dealer-created one-off service may never join a guide
     * taxonomy. A service with no operation code can therefore never be a specialty claim, which
     * is correct — it is general work.
     */
    @Column(name = "operation_code")
    private String operationCode;

    /** False on the delete tombstone: kept, never resolved, so a retired code stops validating. */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** Envelope {@code aggregateVersion} — the monotonic stale-event guard (#1486). */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
