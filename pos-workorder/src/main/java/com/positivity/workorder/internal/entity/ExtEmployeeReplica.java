package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read-only employment-status replica fed by {@code people.events.v1}
 * ({@code people.employee.updated}, ADR-0044 §6, #2119). pos-people owns the facts; this module
 * only needs to know whether a person is still employed, to keep an offboarded technician off the
 * roster and refuse to assign one (#2120).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_people_employee")
public class ExtEmployeeReplica extends TenantScopedEntity {

    /**
     * Employment statuses that end a person's ability to hold work: they are off the roster and
     * cannot be assigned. ACTIVE and ON_LEAVE are not in this set, and neither is an absent row
     * (replica lag must not take a shop offline).
     */
    public static final Set<String> INACTIVE_EMPLOYMENT_STATUSES = Set.of("TERMINATED", "DISABLED", "SUSPENDED");

    @Id
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "status_effective_at")
    private Instant statusEffectiveAt;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether {@code status} is one of {@link #INACTIVE_EMPLOYMENT_STATUSES}. */
    public static boolean isInactiveStatus(@Nullable String status) {
        return status != null && INACTIVE_EMPLOYMENT_STATUSES.contains(status.toUpperCase(Locale.ROOT));
    }

    /**
     * The row that describes a person's current employment: the latest by {@code statusEffectiveAt},
     * falling back to the fact's emission time ({@code aggregateVersion} is epoch millis at the
     * producer) when the fact carried none; ties broken by {@code aggregateVersion}, then
     * {@code updatedAt}. A person may hold several employee rows over time (rehire).
     *
     * <p>The fallback is deliberately the producer's clock, never this replica's {@code updatedAt}:
     * that column is stamped at ingest, so a manifest-driven replay of an old TERMINATED fact with
     * no {@code statusEffectiveAt} would otherwise outrank a genuine, earlier-ingested ACTIVE rehire
     * and lock the person out until pos-people re-emitted the ACTIVE row.
     */
    @NonNull
    public static Optional<ExtEmployeeReplica> latest(@NonNull Collection<ExtEmployeeReplica> rows) {
        return rows.stream()
                .max(Comparator.comparing(ExtEmployeeReplica::effectiveInstant)
                        .thenComparingLong(ExtEmployeeReplica::getAggregateVersion)
                        .thenComparing(ExtEmployeeReplica::getUpdatedAt));
    }

    private Instant effectiveInstant() {
        return statusEffectiveAt != null ? statusEffectiveAt : Instant.ofEpochMilli(aggregateVersion);
    }

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
