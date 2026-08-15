package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Applied-version log for the purchase-order projection (#1333). */
public interface ExtPurchaseOrderReceiptRepository extends JpaRepository<ExtPurchaseOrderReceipt, UUID> {}
