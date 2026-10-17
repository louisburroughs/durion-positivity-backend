package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtOrderPurchaseOrder;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The replica of pos-order's purchase orders (#2515). Written only by the purchase-order fact consumer. */
public interface ExtOrderPurchaseOrderRepository extends JpaRepository<ExtOrderPurchaseOrder, UUID> {}
