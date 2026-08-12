package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-binding scheduler lease and checkpoint (binding decision 4). One row per schedulable binding,
 * keyed by {@code bindingId} so a binding cannot be claimed twice.
 *
 * <h2>Reads through JPA, writes through SQL</h2>
 *
 * Every state transition — claim, heartbeat, checkpoint, release — happens as an atomic
 * {@code UPDATE} in {@link com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository},
 * <strong>never by mutating this entity and flushing</strong>. A read-then-write through JPA is
 * exactly the race the lease exists to prevent: two instances both read an expired lease and both
 * decide they own it.
 *
 * <p>Consequently the setters here are for tests and reads only. {@code @Version} is kept because the
 * native updates increment it, so a stale in-memory copy cannot be flushed over a claim taken by
 * someone else.
 *
 * <h2>Time authority</h2>
 *
 * {@link #leasedUntil}, {@link #lastHeartbeatAt} and {@link #checkpointAt} are computed by the
 * <em>database</em> ({@code now() + N * INTERVAL '1' SECOND}), never in the JVM. Instance clocks
 * drift, and a lease whose expiry is decided by the claimant's own clock is not a lease.
 *
 * <p>{@link #updatedAt} is the one column with two writers, and the split is deliberate: every lease
 * <em>transition</em> sets it to the database's {@code now()} in the same atomic {@code UPDATE} that
 * performs the transition, while {@code @LastModifiedDate} only ever applies at JPA insert — the one
 * moment there is no transition to attribute it to. Nothing about lease safety reads {@code updatedAt};
 * it is operational forensics, so a JVM timestamp on the insert row costs nothing, and routing it
 * through the auditing listener is what ADR-0024 requires of every entity in the fleet.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_schedule_lease",
        indexes = {@Index(name = "idx_slease_leased_until", columnList = "leased_until")})
@EntityListeners(AuditingEntityListener.class)
public class SupplierScheduleLeaseEntity {

    /**
     * The binding this lease governs. Also the primary key: one lease per binding, by construction.
     *
     * <h3>An assigned identifier that still carries {@code @UUIDv7Id}</h3>
     *
     * This id is <strong>never generated</strong> — it is the binding's own identity, so a lease row with
     * an invented id would be a lease on nothing. {@code @UUIDv7Id} is present because the fleet-wide
     * ADR-0013 rule ({@code EntityStandardsArchitectureTest}) requires every UUID {@code @Id} to declare
     * it, and it is safe here only because {@code UUIDv7HibernateGenerator} returns
     * {@code currentValue != null ? currentValue : generate()} and reports
     * {@code allowAssignedIdentifiers() == true}: an assigned id always wins.
     *
     * <p>What stops a <em>minted</em> id — one Hibernate generates because the caller supplied none — from
     * becoming a lease on a binding that does not exist is {@code fk_slease_binding}: {@code binding_id} is
     * a foreign key to {@code supplier_endpoint_binding}, so an invented UUID fails the insert. That FK is
     * what makes the {@code @UUIDv7Id} annotation safe to carry here, not the generator's
     * assigned-value-wins behaviour alone, and
     * {@code SupplierScheduleLeaseRepositoryTest.aMintedBindingIdCannotBecomeAnOrphanLease} pins it. If a
     * future migration ever drops that FK, this annotation stops being harmless and needs a guard.
     *
     * <p>Note for anyone tempted by a {@code @PrePersist} null-check instead: it does not work. The callback
     * fires <em>after</em> identifier generation, so by the time it runs the field is already populated with
     * a generated value and the check can never see null. Verified, not assumed — an earlier attempt at
     * exactly that guard was dead code, and the test that was written to defend it is what exposed both this
     * ordering and the existence of the FK above.
     */
    @Id
    @UUIDv7Id
    @Column(name = "binding_id", nullable = false, updatable = false)
    private UUID bindingId;

    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 64)
    private SupplierCapability capability;

    /**
     * Identifies the run currently holding the lease. Every mutation is guarded on it, so a run whose
     * lease was stolen cannot keep writing — that guard is what makes takeover safe.
     */
    @Column(name = "owner_token", length = 100)
    private String ownerToken;

    /** DB-computed expiry. {@code null} means unheld; a past value means expired and claimable. */
    @Column(name = "leased_until")
    private Instant leasedUntil;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /**
     * End of the last window successfully processed. Advances <em>only</em> on success and only
     * inside the same transaction as the batch page it describes, so a missed run self-heals by
     * reprocessing from here rather than silently skipping a window.
     */
    @Column(name = "checkpoint_at")
    private Instant checkpointAt;

    @Column(name = "last_run_started_at")
    private Instant lastRunStartedAt;

    @Column(name = "last_run_outcome", length = 32)
    private String lastRunOutcome;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Set by the auditing listener at insert; every later transition overwrites it with database time. */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
