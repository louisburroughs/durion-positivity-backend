package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Scheduler lease transitions (binding decision 4), all as atomic single-statement updates.
 *
 * <h2>Why every method is a native {@code UPDATE ... WHERE}</h2>
 *
 * The affected-row count <em>is</em> the answer: 1 means this caller now holds (or still holds) the
 * lease, 0 means someone else does. There is deliberately no read-then-write anywhere, because two
 * instances reading the same expired lease and both concluding they own it is precisely the failure
 * the lease prevents.
 *
 * <h2>Why the time arithmetic is inline SQL</h2>
 *
 * {@code now() + CAST(:seconds AS INTEGER) * INTERVAL '1' SECOND} is evaluated by the database, so
 * expiry never depends on a pod's clock (decision 4: never JVM time). The exact form was chosen for
 * portability and verified against H2 in PostgreSQL mode as well as being standard PostgreSQL: a bound
 * parameter must be CAST, because H2 cannot infer the type of {@code ? * INTERVAL} and fails with
 * "UNKNOWN * INTERVAL SECOND".
 */
public interface SupplierScheduleLeaseRepository extends JpaRepository<SupplierScheduleLeaseEntity, UUID> {

    /**
     * Claims the lease if it is unheld or expired, in one atomic statement.
     *
     * <p>The predicate accepts {@code leased_until IS NULL} as well as a past value: a freshly created
     * row, and a row released by nulling its timestamp, must both be claimable. Treating null as
     * "held" in one place and "expired" in another is how a binding becomes permanently unschedulable.
     *
     * @param bindingId the binding to claim
     * @param ownerToken token identifying this run
     * @param leaseSeconds lease duration; expiry is computed by the database
     * @return 1 when the lease was taken, 0 when another run holds it
     */
    @Modifying
    @Query(
            value = "UPDATE supplier_schedule_lease SET owner_token = :ownerToken,"
                    + " leased_until = now() + CAST(:leaseSeconds AS INTEGER) * INTERVAL '1' SECOND,"
                    + " last_heartbeat_at = now(), last_run_started_at = now(), updated_at = now(),"
                    + " version = version + 1"
                    + " WHERE binding_id = :bindingId"
                    + " AND (leased_until IS NULL OR leased_until < now())",
            nativeQuery = true)
    int claim(
            @Param("bindingId") @NonNull UUID bindingId,
            @Param("ownerToken") @NonNull String ownerToken,
            @Param("leaseSeconds") int leaseSeconds);

    /**
     * Extends the lease, guarded on ownership <em>and</em> on the lease not having already expired.
     *
     * <p>Both guards matter: without the owner check a stolen lease could be extended by its previous
     * holder, and without the expiry check a run that stalled past its own lease could resurrect a
     * claim another run has since taken.
     *
     * @return 1 when extended, 0 when this run no longer owns a live lease
     */
    @Modifying
    @Query(
            value = "UPDATE supplier_schedule_lease SET"
                    + " leased_until = now() + CAST(:leaseSeconds AS INTEGER) * INTERVAL '1' SECOND,"
                    + " last_heartbeat_at = now(), updated_at = now(), version = version + 1"
                    + " WHERE binding_id = :bindingId AND owner_token = :ownerToken"
                    + " AND leased_until > now()",
            nativeQuery = true)
    int heartbeat(
            @Param("bindingId") @NonNull UUID bindingId,
            @Param("ownerToken") @NonNull String ownerToken,
            @Param("leaseSeconds") int leaseSeconds);

    /**
     * Advances the checkpoint, owner-guarded.
     *
     * <p>Must be called inside the <strong>same transaction as the batch page it describes</strong>
     * (decision 4). A checkpoint that commits independently of its page is how a window gets silently
     * skipped: the page rolls back, the checkpoint does not, and nothing ever reprocesses it.
     *
     * @return 1 when advanced, 0 when this run's lease was stolen — the caller must then abort
     */
    @Modifying
    @Query(
            value = "UPDATE supplier_schedule_lease SET checkpoint_at = :checkpointAt,"
                    + " updated_at = now(), version = version + 1"
                    + " WHERE binding_id = :bindingId AND owner_token = :ownerToken"
                    + " AND leased_until > now()",
            nativeQuery = true)
    int advanceCheckpoint(
            @Param("bindingId") @NonNull UUID bindingId,
            @Param("ownerToken") @NonNull String ownerToken,
            @Param("checkpointAt") @NonNull Instant checkpointAt);

    /**
     * Releases the lease by expiring it now and clearing the owner, owner-guarded so a run that lost
     * its lease cannot release the new holder's claim.
     *
     * <p>Sets {@code leased_until = now()} rather than a null, and clears {@code owner_token}, keeping
     * the row consistent with {@code chk_slease_claim_consistent}.
     *
     * @return 1 when released, 0 when this run did not hold it
     */
    @Modifying
    @Query(
            value = "UPDATE supplier_schedule_lease SET owner_token = NULL, leased_until = NULL,"
                    + " last_run_outcome = :outcome, updated_at = now(), version = version + 1"
                    + " WHERE binding_id = :bindingId AND owner_token = :ownerToken",
            nativeQuery = true)
    int release(
            @Param("bindingId") @NonNull UUID bindingId,
            @Param("ownerToken") @NonNull String ownerToken,
            @Param("outcome") @NonNull String outcome);

    /**
     * Whether this run still holds a live lease, decided by database time.
     *
     * @return 1 when still owned and unexpired
     */
    @Query(
            value = "SELECT COUNT(*) FROM supplier_schedule_lease WHERE binding_id = :bindingId"
                    + " AND owner_token = :ownerToken AND leased_until > now()",
            nativeQuery = true)
    long countLiveOwnership(
            @Param("bindingId") @NonNull UUID bindingId, @Param("ownerToken") @NonNull String ownerToken);

    /** Lease rows for the given bindings, for the scheduler to decide what is due. */
    @NonNull
    List<SupplierScheduleLeaseEntity> findByBindingIdIn(@NonNull List<UUID> bindingIds);
}
