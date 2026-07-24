package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ReturnOrderLine;
import com.positivity.order.internal.entity.ReturnOrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnOrderLineRepository extends JpaRepository<ReturnOrderLine, UUID> {

    /**
     * Σ returnQty already booked against one sold line across return orders whose status is not in
     * {@code excludedStatuses} (CANCELLED/REJECTED do not count toward the cap). Reserves in-flight
     * returns so a second return of the remainder is bounded (spec R5.2).
     */
    @Query("select coalesce(sum(l.returnQty), 0) from ReturnOrderLine l "
            + "where l.originalOrderLineId = :lineId and l.returnOrder.status not in :excluded")
    int sumReturnedQty(
            @Param("lineId") UUID originalOrderLineId,
            @Param("excluded") Collection<ReturnOrderStatus> excludedStatuses);

    /** Grouped Σ returnQty per sold line for a set of lines — the returnableQty read model. */
    @Query("select l.originalOrderLineId, coalesce(sum(l.returnQty), 0) from ReturnOrderLine l "
            + "where l.originalOrderLineId in :lineIds and l.returnOrder.status not in :excluded "
            + "group by l.originalOrderLineId")
    List<Object[]> sumReturnedQtyByLine(
            @Param("lineIds") Collection<UUID> lineIds,
            @Param("excluded") Collection<ReturnOrderStatus> excludedStatuses);
}
