package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLineEntity, UUID> {}
