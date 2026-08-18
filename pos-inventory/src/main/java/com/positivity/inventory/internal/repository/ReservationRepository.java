package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.ReservationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    List<ReservationEntity> findByStockItemId(UUID stockItemId);

    Optional<ReservationEntity> findByWorkorderLineId(UUID workorderLineId);

    Optional<ReservationEntity> findBySalesOrderLineId(UUID salesOrderLineId);

    /**
     * Looks a reservation up by whichever demand line it's keyed on (CAP #1315): pass the same id
     * for both parameters when the caller only knows "this line id," not which kind it is. Safe
     * because the two id spaces never collide — each is independently minted by its own module.
     */
    Optional<ReservationEntity> findByWorkorderLineIdOrSalesOrderLineId(UUID workorderLineId, UUID salesOrderLineId);

    /**
     * Open reservation remainders for one SKU (odoo-parity A2, issue #1028): sum of
     * {@code requiredQuantity - allocatedQuantity} (per reservation, floored at zero) over
     * reservations in the given statuses, optionally bounded to a due-date horizon.
     *
     * <p>Reservations carry no site — the remainder is site-agnostic demand. Verified entity
     * semantics: {@code allocatedQuantity} includes SOFT allocations (a freshly created
     * reservation is fully soft-allocated and contributes 0); the remainder becomes positive
     * when reallocation or backorder flows leave required quantity uncovered.
     *
     * <p>With {@code boundByDueDate = true}, reservations with a {@code NULL} due date are
     * excluded; with {@code false} they are included and {@code horizon} is ignored.
     */
    @Query("""
                        SELECT COALESCE(SUM(CASE WHEN r.requiredQuantity - r.allocatedQuantity > 0
                                                 THEN r.requiredQuantity - r.allocatedQuantity
                                                 ELSE 0 END), 0)
                        FROM ReservationEntity r
                        WHERE r.stockItemId = :stockItemId
                          AND r.status IN :statuses
                          AND (:boundByDueDate = FALSE
                               OR (r.dueDateTime IS NOT NULL AND r.dueDateTime <= :horizon))
                        """)
    Long sumOpenRemainderForSku(
            @Param("stockItemId") UUID stockItemId,
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("boundByDueDate") boolean boundByDueDate,
            @Param("horizon") Instant horizon);

    /**
     * Distinct due instants of open reservation demand for one SKU within a horizon
     * (odoo-parity F3, issue #1041): candidate cutoff instants for the stock-out deadline
     * approximation. Undated reservations are excluded, matching the bounded variant of
     * {@link #sumOpenRemainderForSku}.
     */
    @Query("""
                        SELECT DISTINCT r.dueDateTime
                        FROM ReservationEntity r
                        WHERE r.stockItemId = :stockItemId
                          AND r.status IN :statuses
                          AND r.dueDateTime IS NOT NULL
                          AND r.dueDateTime <= :horizon
                        """)
    List<Instant> findDistinctDueInstantsForSku(
            @Param("stockItemId") UUID stockItemId,
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("horizon") Instant horizon);
}
