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
 *
 * <h2>Why the lifecycle mutations declare REQUIRES_NEW — and why {@code now()} is still correct</h2>
 *
 * <strong>PostgreSQL's {@code now()} is {@code transaction_timestamp()}: it returns the moment the
 * transaction began, and does not advance for the transaction's lifetime.</strong> That makes the
 * transaction boundary part of the lease's correctness, not a matter of taste. {@link #heartbeat},
 * {@link #countLiveOwnership} and {@link #release} are all called from <em>inside</em> a long-running batch
 * page, and joining that transaction would have broken each of them in a different way:
 *
 * <ul>
 *   <li><strong>{@code heartbeat}</strong> would extend the lease from the moment the page <em>started</em>,
 *       not from now. A heartbeat five minutes into a ten-minute page would compute an expiry already in the
 *       past — so the operation whose entire purpose is to keep a long run's lease alive would quietly fail
 *       to extend it, and the lease would be stolen mid-run.
 *   <li><strong>{@code countLiveOwnership}</strong> would compare {@code leased_until} against that same
 *       frozen timestamp, so a lease that had in fact expired would still read as live.
 *   <li><strong>{@code release}</strong> would roll back with a failed page, leaving the lease held until
 *       natural expiry — the one case where prompt release matters most.
 * </ul>
 *
 * {@code REQUIRES_NEW} makes each statement its own transaction, so {@code transaction_timestamp()} is the
 * statement's own time and {@code release} commits regardless of the page's fate.
 *
 * <p><strong>The boundary is declared on {@link
 * com.positivity.supplier.internal.service.SupplierScheduleCoordinator}, not here.</strong> That is where the
 * coordination decision lives, and putting it on the repository would additionally make every
 * {@code @DataJpaTest} of these queries unable to see its own uncommitted fixtures — the tests would have to
 * be rewritten around a constraint that has nothing to do with what they prove.
 *
 * <p><strong>Decision: {@code now()} kept, {@code clock_timestamp()} rejected.</strong>
 * {@code clock_timestamp()} would also give a truly current reading, but it is PostgreSQL-only — H2 does not
 * implement it, so every test that proves lease behaviour under contention would stop running, and this
 * module's lease correctness is established by exactly those tests. Fixing the boundary is portable and
 * fixes the release-rollback problem too, which {@code clock_timestamp()} would not.
 *
 * <p><strong>{@link #advanceCheckpoint} deliberately does NOT get {@code REQUIRES_NEW}.</strong> Binding
 * decision 4 requires the checkpoint to commit in the same transaction as the page it describes; an
 * independently committing checkpoint would mark a window processed that had been rolled back, and silently
 * skip it forever. Its {@code now()} being the page's start time is harmless: the value it stores is the
 * caller-supplied window end, not a clock reading.
 *
 * <p>H2 cannot reproduce the underlying hazard — its {@code now()} is statement-scoped, not
 * transaction-scoped — so no behavioural test can demonstrate it. The boundary is pinned structurally
 * instead, by {@code SupplierScheduleCoordinatorTest.leaseLifecycleOperationsRunInTheirOwnTransaction}.
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
     * <p>That has a consequence for the {@code leased_until > now()} predicate, and it is the one place in this
     * interface where the frozen-clock behaviour is <em>load-bearing rather than harmless</em>. Because this
     * runs in the page's transaction, PostgreSQL's {@code now()} is the moment the page STARTED — so the guard
     * asks "was the lease live when this page began?", not "is it live now?". A run whose lease expired
     * mid-page can therefore still advance its checkpoint.
     *
     * <p>That is the correct answer, not a defect: the owner-token half of the predicate still fails if another
     * run has actually <em>taken</em> the lease, and if nobody took it then the work in this page really was
     * done and the window really is complete. Refusing the checkpoint on a technically-expired-but-unstolen
     * lease would reprocess a window that succeeded. The {@code updated_at = now()} in the SET clause is
     * cosmetic by the same mechanism.
     *
     * <p>The stolen case is what must be caught, and it is: {@code SupplierScheduleCoordinator.runPage} treats
     * zero affected rows as a lost lease and rolls the page back with it.
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
     * <p>Sets <strong>both</strong> {@code owner_token} and {@code leased_until} to {@code NULL}. That is what
     * keeps the row consistent with {@code chk_slease_claim_consistent}, which requires the two to be either
     * both null or both populated — and it is why a released lease is immediately claimable, since the claim
     * predicate treats a null expiry as unheld.
     *
     * <p>An earlier version of this javadoc said it set {@code leased_until = now()} rather than a null, which
     * the query directly contradicts. It also would not have worked: {@code now()} with a null owner token
     * violates the CHECK constraint, and the same mistake in a test's {@code expireLease} helper is what made
     * that constraint fire during this wave.
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
