package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInventoryGoodsReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The replica of pos-inventory's goods receipts (#2750). Written only by the goods-receipt fact consumer. */
public interface ExtInventoryGoodsReceiptRepository extends JpaRepository<ExtInventoryGoodsReceipt, UUID> {}
