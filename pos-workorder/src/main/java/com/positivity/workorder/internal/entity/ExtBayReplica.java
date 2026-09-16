package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only service-bay replica fed by {@code location.events.v1} (ADR-0044 §6, #1656).
 *
 * <p>pos-location owns the bay aggregate; nothing in this module may write the table except the
 * event consumer. Same shape and rules as {@link ExtLocationReplica}: the owner's monotonic
 * {@code aggregateVersion} guards against applying a stale fact, and a <em>deactivated</em> bay
 * (status {@code OUT_OF_SERVICE}) is retained as an {@code active=false} row, so an assignment to a
 * decommissioned bay can still be named on the board.
 *
 * <p>A <em>deleted</em> bay is a different fact and does remove the row: {@code location.bay.deleted}
 * says the owner's aggregate no longer exists, and this replica mirrors the owner rather than
 * outliving it — exactly as {@link ExtLocationReplica} does for {@code location.location.deleted}.
 * That is not a hole in the board: the dispatch panel renders a row for any resource open work still
 * points at, with a null name, whether or not a replica row survives for it.
 *
 * <p>This replica is why the dispatch board can finally show a bay <em>name</em>: before it
 * existed, {@code BayStatus.bayName} was declared and never populated, because bay identity lives
 * in another domain and ADR-0044 forbids reaching for it synchronously.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_bay")
public class ExtBayReplica extends TenantScopedEntity {

    @Id
    @Column(name = "bay_id", nullable = false)
    private UUID bayId;

    /** The site this bay belongs to — the dashboard scopes its bay panel by this column. */
    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "name")
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** Owner's bay type discriminator. Display-only once the two fields below exist (CAP-325 D5). */
    @Column(name = "bay_type", length = 50)
    private String bayType;

    /**
     * Catalog operation codes this bay type is the only one able to perform (CAP-325 D14). Empty
     * means a general bay — eligible for every operation no specialty bay claims. NULL means the
     * publisher predates the field and nothing may be inferred; never read NULL as "none".
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "service_capability_codes")
    private List<String> serviceCapabilityCodes;

    /** How many vehicles the bay physically holds; NULL until the publisher emits it. */
    @Column(name = "max_concurrent_vehicles")
    private Integer maxConcurrentVehicles;

    /** Heaviest GVWR class (1–8) the bay accepts; NULL when unconstrained or not yet published (D13). */
    @Column(name = "max_duty_class")
    private Integer maxDutyClass;

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
        return UUIDv7Id.class;
    }
}
