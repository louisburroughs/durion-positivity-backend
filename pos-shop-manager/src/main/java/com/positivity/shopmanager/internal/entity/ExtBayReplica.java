package com.positivity.shopmanager.internal.entity;

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
 * Read-only service-bay replica fed by {@code location.events.v1} (ADR-0044 §6, #1658).
 *
 * <p>pos-location owns the bay aggregate; only {@link
 * com.positivity.shopmanager.internal.service.LocationEventsListener} writes this table (R3).
 *
 * <p>Not to be confused with this module's vestigial {@link Bay} entity, which carries nothing but
 * an id and timestamps and predates the move of bay ownership to pos-location — visible in
 * {@code permissions.yaml}, where {@code shop:bay:view} is deprecated in favour of
 * {@code location:bay:read}. The dashboard reads this replica, never that table.
 *
 * <p>{@code active=false} rows are retained as tombstones: a decommissioned bay may still be
 * referenced by open work, and dropping the row would make that work's unit unnameable.
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

    /** The site this bay belongs to — the dashboard scopes its unit roster by this column. */
    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "name")
    private String name;

    /** Owner's bay type discriminator, stored verbatim (#2023/#2021). */
    @Column(name = "bay_type", length = 64)
    private String bayType;

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
    @Column(name = "service_capability_codes", columnDefinition = "text[]")
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

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
