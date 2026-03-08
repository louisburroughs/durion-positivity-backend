package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {
}
