package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    Optional<SalesOrder> findByCreationIdempotencyKey(String creationIdempotencyKey);

    @Query("select o from SalesOrder o"
            + " where (:clerkId is null or o.clerkId = :clerkId)"
            + " and (:terminalId is null or o.terminalId = :terminalId)"
            + " and (:status is null or o.status = :status)"
            + " order by o.createdAt desc")
    Page<SalesOrder> search(
            @Param("clerkId") String clerkId,
            @Param("terminalId") String terminalId,
            @Param("status") SalesOrderStatus status,
            Pageable pageable);
}
