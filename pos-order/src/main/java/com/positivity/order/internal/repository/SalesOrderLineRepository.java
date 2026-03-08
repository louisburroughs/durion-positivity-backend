package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, UUID> {
    List<SalesOrderLine> findByOrder_OrderId(UUID orderId);
}
