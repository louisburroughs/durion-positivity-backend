package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrderLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, UUID> {
    List<SalesOrderLine> findByOrder_OrderId(UUID orderId);
}
