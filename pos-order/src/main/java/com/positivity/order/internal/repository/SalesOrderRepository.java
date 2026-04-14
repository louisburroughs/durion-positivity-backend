package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrder;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {}
