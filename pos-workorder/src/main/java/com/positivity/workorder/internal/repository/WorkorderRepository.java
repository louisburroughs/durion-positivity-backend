package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Workorder;
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
     * @param scheduledDate the date to query
     * @param locationId    the location identifier
     * @return list of matching workorders
     */
    @NonNull
    List<Workorder> findByScheduledDateAndLocationId(@NonNull LocalDate scheduledDate, @NonNull UUID locationId);

    /**
     * Free-text workorder search matching the workorder number (contains), a resolved
     * customer id (from a name search), or the workorder id directly, optionally
     * restricted to an exact customer, vehicle, status, creation-date window, and/or
     * technician (E12, #1600).
     *
     * @param q            free-text term matched against workorderNumber (case-insensitive contains)
     * @param customerIds  customer ids resolved from a name search (must be non-empty for JPQL IN)
     * @param idQuery      the query parsed as a UUID, or {@code null} if not a UUID
     * @param customerId   exact customer filter, or {@code null} for no restriction
     * @param vehicleId    exact vehicle filter, or {@code null} for no restriction
     * @param status       exact status filter, or {@code null} for no restriction (mirrors
     *                     InvoiceRepository#searchByQuery's single-status filter, #1599/E11)
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
            + "AND (:status IS NULL OR w.status = :status) "
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
            @Param("q") String q,
            @Param("customerIds") Collection<UUID> customerIds,
            @Param("idQuery") UUID idQuery,
            @Param("customerId") UUID customerId,
            @Param("vehicleId") UUID vehicleId,
            @Param("status") @Nullable WorkorderStatus status,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            @Param("technicianId") UUID technicianId,
            Pageable pageable);

    /**
     * Whether a workorder already carries the given human number. Used by number
     * generation to guarantee global uniqueness.
     *
     * @param workorderNumber candidate human number
     * @return true if any workorder already uses it
     */
    boolean existsByWorkorderNumber(@NonNull String workorderNumber);
}
