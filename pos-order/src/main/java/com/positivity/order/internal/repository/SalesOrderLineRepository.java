package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, UUID> {
}
