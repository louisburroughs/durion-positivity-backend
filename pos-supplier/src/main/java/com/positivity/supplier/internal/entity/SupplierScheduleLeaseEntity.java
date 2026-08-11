package com.positivity.supplier.internal.entity;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * {@link #leasedUntil}, {@link #lastHeartbeatAt} and {@link #updatedAt} are computed by the
 * <em>database</em> ({@code now() + N * INTERVAL '1' SECOND}), never in the JVM. Instance clocks
 * drift, and a lease whose expiry is decided by the claimant's own clock is not a lease.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_schedule_lease",
        indexes = {@Index(name = "idx_slease_leased_until", columnList = "leased_until")})
public class SupplierScheduleLeaseEntity {

    /** The binding this lease governs. Also the primary key: one lease per binding, by construction. */
    @Id
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
