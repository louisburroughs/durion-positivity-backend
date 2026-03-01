package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkorderRepository extends JpaRepository<Workorder, UUID> {
    /**
     * Find all WorkOrders associated with a specific Estimate
     * 
     * @param estimateId the ID of the estimate
     * @return list of WorkOrders linked to this estimate
     */
    @NonNull
    List<Workorder> findAllByEstimateId(@NonNull UUID estimateId);

    /**
     * Find the first workorder associated with a specific estimate.
     * Used for idempotency checks in promotion validation.
     *
     * @param estimateId the ID of the estimate
     * @return Optional containing the first matching workorder if found
     */
    @NonNull
    Optional<Workorder> findFirstByEstimateId(@NonNull UUID estimateId);

    /**
     * Find workorder by generated invoice ID.
     *
     * @param invoiceId generated invoice ID
     * @return optional workorder linked to the invoice
     */
    @NonNull
    Optional<Workorder> findByInvoiceId(@NonNull UUID invoiceId);

    @NonNull
    Page<Workorder> findByShopIdAndStatusIn(@NonNull UUID shopId,
            @NonNull Collection<WorkorderStatus> statuses,
            @NonNull Pageable pageable);

    @NonNull
    Page<Workorder> findByStatusIn(@NonNull Collection<WorkorderStatus> statuses,
            @NonNull Pageable pageable);
}
