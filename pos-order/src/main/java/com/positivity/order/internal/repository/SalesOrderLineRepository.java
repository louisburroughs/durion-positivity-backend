package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrderLine;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, UUID> {
    List<SalesOrderLine> findByOrder_OrderId(UUID orderId);

    Optional<SalesOrderLine> findByOrder_OrderIdAndClientLineUuid(UUID orderId, UUID clientLineUuid);

    /**
     * Pessimistic-write lock on a sold line, taken at return creation so concurrent returns of the
     * same remainder serialize on the row and the qty-cap invariant holds (spec R5.2).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SalesOrderLine l where l.orderLineId = :orderLineId")
    Optional<SalesOrderLine> findByOrderLineIdForUpdate(@Param("orderLineId") UUID orderLineId);
}
