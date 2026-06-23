package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
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
     * Free-text workorder search matching either a resolved customer id (from a name
     * search) or the workorder id directly.
     *
     * @param customerIds customer ids resolved from a name search (must be non-empty for JPQL IN)
     * @param idQuery     the query parsed as a UUID, or {@code null} if not a UUID
     * @param pageable    pagination configuration
     * @return page of matching workorders
     */
    @Query("SELECT w FROM Workorder w WHERE w.customerId IN :customerIds "
            + "OR (:idQuery IS NOT NULL AND w.id = :idQuery)")
    Page<Workorder> searchByQuery(
            @Param("customerIds") Collection<UUID> customerIds, @Param("idQuery") UUID idQuery, Pageable pageable);
}
