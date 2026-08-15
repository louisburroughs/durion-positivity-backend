package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Projected purchase orders owned by pos-order (ADR-0044 R3). Read-only outside the consumer. */
public interface ExtPurchaseOrderRepository extends JpaRepository<ExtPurchaseOrderReplica, UUID> {}
