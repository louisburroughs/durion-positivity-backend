package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The {@code aggregateVersion} counter for a tenant's whole bay specialty map
 * (DECISION-LOCATION-025), one row per tenant.
 *
 * <p>{@code bay_specialty_operation} is several rows per tenant, not one aggregate with its own
 * JPA {@code @Version} — there is no single row {@code location.bay-specialty-map.updated} could
 * version itself from, the way {@code BayUpdatedV1} versions from {@code BayEntity.version}. This
 * table stands in as that aggregate: {@code BaySpecialtyMapPublisher} bumps it every time the map
 * actually changes for a tenant (today, only tenant provisioning) and reads it, without bumping,
 * for the once-per-tenant startup republish that lets replicas fill.
 *
 * <p>Tenant-scoped; the unique constraint on {@code tenant_id} alone (not paired with another
 * column) is what keeps this to exactly one row per tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "bay_specialty_map_version",
        uniqueConstraints = @UniqueConstraint(name = "uq_bay_specialty_map_version_tenant", columnNames = "tenant_id"))
@EntityListeners(AuditingEntityListener.class)
public class BaySpecialtyMapVersionEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "version", nullable = false)
    private long version;

    /**
     * ADR-0024: populated by {@link AuditingEntityListener} on every persist/update via this
     * module's {@code auditingDateTimeProvider} (bound to the injected {@code Clock}), the same as
     * every other entity here. {@link BaySpecialtyMapPublisher} also sets it explicitly before
     * save so a caller reading the in-memory instance immediately after sees a value without
     * waiting on the listener's flush-time callback; the two never disagree since both read the
     * same {@code Clock}.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
