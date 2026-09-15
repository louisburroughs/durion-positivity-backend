package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkorderRepository extends JpaRepository<Workorder, UUID> {
    /**
     * Find all WorkOrders associated with a specific Estimate
     *
     * @param estimateId the ID of the estimate
     * @return list of WorkOrders linked to this estimate
     */
    @NonNull
    List<Workorder> findAllByEstimate_Id(@NonNull UUID estimateId);

    /**
     * Find the first workorder associated with a specific estimate.
     * Used for idempotency checks in promotion validation.
     *
     * @param estimateId the ID of the estimate
     * @return Optional containing the first matching workorder if found
     */
    @NonNull
    Optional<Workorder> findFirstByEstimate_Id(@NonNull UUID estimateId);

    /**
     * Find workorder by generated invoice ID.
     *
     * @param invoiceId generated invoice ID
     * @return optional workorder linked to the invoice
     */
    @NonNull
    Optional<Workorder> findByInvoiceId(@NonNull UUID invoiceId);

    @NonNull
    Page<Workorder> findByShopIdAndStatusIn(
            @NonNull UUID shopId, @NonNull Collection<WorkorderStatus> statuses, @NonNull Pageable pageable);

    @NonNull
    Page<Workorder> findByStatusIn(@NonNull Collection<WorkorderStatus> statuses, @NonNull Pageable pageable);

    /**
     * The multi-location WIP page narrowed to a caller's location reach (ADR-0061 §3, #1872).
     *
     * @param shopIds the shops the caller may see; never empty — the service answers an empty
     *     page for an empty reach rather than handing {@code IN ()} to the database
     * @param statuses the active WIP statuses
     * @param pageable page request
     * @return the page of workorders at those shops
     */
    @NonNull
    Page<Workorder> findByShopIdInAndStatusIn(
            @NonNull Collection<UUID> shopIds,
            @NonNull Collection<WorkorderStatus> statuses,
            @NonNull Pageable pageable);

    /** Projection row for {@link #countGroupedByStatus(Collection)} — one status and its count. */
    interface StatusCount {
        WorkorderStatus getStatus();

        long getCount();
    }

    /**
     * Server-side grouped count of workorders whose status is in {@code statuses}. Returns one row
     * per status that actually has matching rows (statuses with zero matches are absent). Backs the
     * {@code GET /v1/workorders/count} endpoint without loading any workorder rows.
     *
     * @param statuses statuses to include (must be non-empty for the JPQL {@code IN} clause)
     * @return per-status counts for the matching statuses
     */
    @Query("SELECT w.status AS status, COUNT(w) AS count FROM Workorder w "
            + "WHERE w.status IN :statuses GROUP BY w.status")
    @NonNull
    List<StatusCount> countGroupedByStatus(@Param("statuses") @NonNull Collection<WorkorderStatus> statuses);

    /**
     * Find all workorders for a given scheduled date and location.
     * Used by the Daily Dispatch Board Dashboard (CAP-142) to populate the day
     * view.
     *
     * <p>This is the board's day <em>schedule</em>, not its whole roster (#2002). It is exact on
     * the date by design — "what is booked for today" is a question about today — and the board
     * unions it with {@link #findOpenResourceHoldersAtLocation} to pick up the multi-day job that
     * was booked earlier and is still in its bay. Widening the predicate here instead would pull in
     * every closed job from every past date; the union is bounded to work that is still open and
     * still holding a resource, which is precisely the work a dispatcher still has to place.
     *
     * @param scheduledDate the date to query
     * @param locationId    the location identifier
     * @return list of matching workorders
     */
    @NonNull
    List<Workorder> findByScheduledDateAndLocationId(@NonNull LocalDate scheduledDate, @NonNull UUID locationId);

    /**
     * Workorders at {@code locationId} that still hold a resource and are still open, on or before
     * {@code onOrBefore} (#1656).
     *
     * <p>The dispatch board's bay and mobile-unit panels now positively assert {@code AVAILABLE}
     * for every unit they list, so they can no longer derive occupancy from
     * {@link #findByScheduledDateAndLocationId} alone: a multi-day job scheduled on an earlier date
     * is still in the bay today, and reading only today's rows would advertise that bay as free.
     * This query is the occupancy source instead — one query per dashboard render, not one per unit.
     *
     * <p>"Open" is {@code Workorder.isLocked()} expressed in JPQL: CANCELLED is locked, and
     * COMPLETED is locked unless the workorder was reopened (reopening never changes the status, so
     * a plain {@code status NOT IN (COMPLETED, CANCELLED)} would free a bay somebody is working in).
     * The upper date bound is what keeps the fix from over-claiming in the other direction: work
     * scheduled for a future date is booked, not occupying the unit on the requested date. A null
     * {@code scheduledDate} is unscheduled work that is nonetheless holding the resource now, so it
     * is included.
     *
     * <p>The status predicates are written as explicit {@code <> ... OR IS NULL} pairs rather than
     * as bare inequalities because {@code workorder.status} is a nullable column and SQL three-valued
     * logic makes {@code status <> CANCELLED} evaluate to NULL — i.e. excluded, i.e. <em>locked</em>
     * — for a row with no status, while {@link Workorder#isLocked()} reads exactly the same row as
     * open. That disagreement had one visible consequence: a resource-holding workorder with a null
     * status would be dropped from this query and its bay reported AVAILABLE while the entity still
     * considered the job live (#1656). Null now means open on both sides, matching
     * {@code isLocked()}, which is the single authority the panels and conflict detection also use.
     *
     * @param locationId the site whose panels are being rendered
     * @param onOrBefore the dashboard date; rows scheduled after it are excluded
     * @return open, resource-holding workorders at the location
     */
    @Query("SELECT w FROM Workorder w WHERE w.locationId = :locationId AND w.resourceId IS NOT NULL "
            + "AND (w.scheduledDate IS NULL OR w.scheduledDate <= :onOrBefore) "
            + "AND (w.status IS NULL "
            + "OR w.status <> com.positivity.workorder.internal.enums.WorkorderStatus.CANCELLED) "
            + "AND (w.status IS NULL "
            + "OR w.status <> com.positivity.workorder.internal.enums.WorkorderStatus.COMPLETED "
            + "OR w.isReopened = TRUE)")
    @NonNull
    List<Workorder> findOpenResourceHoldersAtLocation(
            @Param("locationId") @NonNull UUID locationId, @Param("onOrBefore") @NonNull LocalDate onOrBefore);

    /**
     * The open workorders currently occupying one exclusive service position (#1984).
     *
     * <p>This is the assignment-time gate {@code findOpenResourceHoldersAtLocation} is not: that one
     * answers a whole site's dispatch board in one query and is bounded by the board's date, which
     * is exactly wrong here — a job scheduled for tomorrow is not yet in the bay for the board, but
     * it <em>does</em> already hold the bay, and letting a second workorder take it would create the
     * double-booking the board would then report. So there is no date bound: open is open.
     *
     * <p>"Open" is {@link Workorder#isLocked()} in JPQL, written as explicit {@code <> … OR IS NULL}
     * pairs for the same reason the sibling query is — {@code status} is nullable and SQL's
     * three-valued logic would silently read a null-status row as locked, freeing a position the
     * entity still considers held. The predicate here must agree with the partial unique index
     * {@code workorder_open_position_uniq} exactly: this query produces the 409 with the occupying
     * workorder named, the index produces the same 409 for two assigns that race past the check.
     *
     * <p>The caller excludes the workorder being assigned, so re-assigning a workorder to the
     * position it already holds is a no-op rather than a self-conflict.
     *
     * <p>{@link com.positivity.workorder.internal.enums.ResourceType#HOLD} positions are never
     * passed here — a lot has no capacity limit — which is the caller's job, not this query's.
     *
     * @param resourceType the kind of position
     * @param resourceId   the position
     * @param excludeWorkorderId the workorder being assigned, which cannot conflict with itself
     * @return open workorders on that position; empty when it is free
     */
    @Query("SELECT w FROM Workorder w WHERE w.resourceId = :resourceId AND w.resourceType = :resourceType "
            + "AND w.id <> :excludeWorkorderId "
            + "AND (w.status IS NULL "
            + "OR w.status <> com.positivity.workorder.internal.enums.WorkorderStatus.CANCELLED) "
            + "AND (w.status IS NULL "
            + "OR w.status <> com.positivity.workorder.internal.enums.WorkorderStatus.COMPLETED "
            + "OR w.isReopened = TRUE)")
    @NonNull
    List<Workorder> findOpenOccupantsOfPosition(
            @Param("resourceType") @NonNull ResourceType resourceType,
            @Param("resourceId") @NonNull UUID resourceId,
            @Param("excludeWorkorderId") @NonNull UUID excludeWorkorderId);

    /**
     * Free-text workorder search matching the workorder number (contains), a resolved
     * customer id (from a name search), or the workorder id directly, optionally
     * restricted to an exact customer, vehicle, status, creation-date window, and/or
     * technician (E12, #1600).
     *
     * <p>{@code q} must not be null — {@code WorkorderSearchController} substitutes the empty string
     * for an absent query term, and the {@code :q = ''} disjunct is what turns that into "no
     * free-text restriction". The non-nullness is load-bearing rather than decorative: apart from
     * that comparison with a literal, this parameter only ever reaches PostgreSQL inside
     * {@code LOWER(CONCAT(…))}, never beside a column, so a null would leave the server resolving
     * {@code unknown || unknown} as {@code bytea} and rejecting the statement at parse time with
     * {@code function lower(bytea) does not exist} — the second failure mode of issue #1891. Every
     * other optional filter here is a UUID or a boolean compared with a column, which the server can
     * type in either direction, which is why they are safe as {@code (:param IS NULL OR …)} clauses.
     *
     * @param q            free-text term matched against workorderNumber (case-insensitive contains);
     *                     never null — an absent term is the empty string
     * @param customerIds  customer ids resolved from a name search (must be non-empty for JPQL IN)
     * @param idQuery      the query parsed as a UUID, or {@code null} if not a UUID
     * @param customerId   exact customer filter, or {@code null} for no restriction
     * @param vehicleId    exact vehicle filter, or {@code null} for no restriction
     * @param statusFilterEnabled whether {@code statuses} restricts the result; {@code false} means
     *                     no status restriction at all. A plain {@code IN} clause cannot be handed an
     *                     empty collection (JPQL rejects it), and the status domain is a small closed
     *                     enum with no id-space to carve out a "never matches" sentinel the way
     *                     {@code customerIds} does — so the "no restriction" case is carried by this
     *                     explicit flag instead, with {@code statuses} holding a harmless non-empty
     *                     placeholder value that is never evaluated once the flag is {@code false}
     *                     (#1676, replacing the prior single-{@code WorkorderStatus} filter mirrored
     *                     from {@code InvoiceRepository#searchByQuery}, #1599/E11).
     * @param statuses     status values to match when {@code statusFilterEnabled} is {@code true};
     *                     must be non-empty regardless (see {@code statusFilterEnabled})
     * @param createdFrom  inclusive lower bound on {@code createdAt}; a null caller-supplied bound is
     *                     widened to a sentinel far in the past by the service layer rather than passed
     *                     as {@code null} here — an untyped {@code null} bound against a temporal
     *                     column is not something Postgres can infer a type for inside a comparison
     * @param createdTo    exclusive upper bound on {@code createdAt}; a null caller-supplied bound is
     *                     widened to a sentinel far in the future by the service layer, for the same
     *                     reason as {@code createdFrom}
     * @param technicianId technician id to match against {@code WorkorderLaborEntry.technicianId} (any
     *                     technician who logged a labor entry on the workorder), or {@code null} for no
     *                     restriction
     * @param pageable     pagination configuration
     * @return page of matching workorders
     */
    @Query("SELECT w FROM Workorder w WHERE (:q = '' "
            + "OR LOWER(w.workorderNumber) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR w.customerId IN :customerIds "
            + "OR (:idQuery IS NOT NULL AND w.id = :idQuery)) "
            + "AND (:customerId IS NULL OR w.customerId = :customerId) "
            + "AND (:vehicleId IS NULL OR w.vehicleId = :vehicleId) "
            + "AND (:statusFilterEnabled = FALSE OR w.status IN :statuses) "
            + "AND w.createdAt >= :createdFrom "
            + "AND w.createdAt < :createdTo "
            + "AND (:technicianId IS NULL OR EXISTS ("
            + "  SELECT 1 FROM WorkorderLaborEntry le "
            + "  WHERE le.workorder = w AND le.technicianId = :technicianId)) "
            // Deterministic default order (newest first) so pagination is stable — without it
            // Postgres returns plan-dependent order and page-1-only consumers silently drop
            // rows. A caller-supplied Pageable sort is appended after this.
            + "ORDER BY w.createdAt DESC")
    Page<Workorder> searchByQuery(
            @Param("q") @NonNull String q,
            @Param("customerIds") Collection<UUID> customerIds,
            @Param("idQuery") @Nullable UUID idQuery,
            @Param("customerId") @Nullable UUID customerId,
            @Param("vehicleId") @Nullable UUID vehicleId,
            @Param("statusFilterEnabled") boolean statusFilterEnabled,
            @Param("statuses") Collection<WorkorderStatus> statuses,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            @Param("technicianId") @Nullable UUID technicianId,
            Pageable pageable);

    /**
     * Whether a workorder already carries the given human number. Used by number
     * generation to guarantee global uniqueness.
     *
     * @param workorderNumber candidate human number
     * @return true if any workorder already uses it
     */
    boolean existsByWorkorderNumber(@NonNull String workorderNumber);

    /** Projection row for {@link #countOpenGroupedByCustomer(Collection)} — one customer and its open count. */
    interface OpenCustomerCount {
        UUID getCustomerId();

        long getOpenWorkorders();
    }

    /**
     * One row per customer holding at least one work order in {@code statuses} (#1855).
     *
     * <p>Grouping server-side is the point: the cross-domain questions this serves ("customers with
     * an open work order who also owe money") need every customer with open work, and the search
     * endpoint pages at 25 rows, so a client-side equivalent is one call per candidate customer.
     * Work orders with no customer are excluded — they cannot join to anything.
     */
    /**
     * ASSIGNED workorders that do not hold both halves of the pair the status stands for (#2011).
     *
     * <p>The migration's whole input: rows created before ASSIGNED meant "a technician <em>and</em> a
     * bay or mobile unit", which therefore claim to be ready to be worked with nobody on them, or
     * nowhere to work them, or both. A HOLD position does not count — a parking space is not
     * somewhere work happens — so the predicate asks for an exclusive resource type rather than any
     * non-null one.
     *
     * <p>Ids rather than entities: each one is transitioned in its own call through the state machine,
     * so the rows are re-read there anyway, and the migration must not hold a large result set open
     * across those writes.
     */
    @Query("SELECT w.id FROM Workorder w "
            + "WHERE w.status = com.positivity.workorder.internal.enums.WorkorderStatus.ASSIGNED "
            + "AND (w.resourceId IS NULL "
            + "OR w.resourceType IS NULL "
            + "OR w.resourceType = com.positivity.workorder.internal.enums.ResourceType.HOLD "
            + "OR NOT EXISTS (SELECT 1 FROM TechnicianAssignment a "
            + "WHERE a.workorder.id = w.id AND a.current = TRUE))")
    @NonNull
    List<UUID> findAssignedWithoutTechnicianAndPosition();

    @Query("SELECT w.customerId AS customerId, COUNT(w) AS openWorkorders FROM Workorder w "
            + "WHERE w.status IN :statuses AND w.customerId IS NOT NULL "
            + "GROUP BY w.customerId ORDER BY COUNT(w) DESC, w.customerId ASC")
    @NonNull
    List<OpenCustomerCount> countOpenGroupedByCustomer(
            @Param("statuses") @NonNull Collection<WorkorderStatus> statuses);
}
