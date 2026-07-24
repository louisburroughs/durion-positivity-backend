package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.GoodsReceiptLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link GoodsReceiptLineEntity} rows.
 *
 * <p>Read-only for the traceability upstream resolver (odoo-parity E5, issue #1051): a lot's
 * (sku, lotNumber) pair matches the goods-receipt lines that brought it in, and each line's
 * {@code goodsReceipt} carries the purchase order and (optional) ASN references. Writes stay with
 * the receiving/accrual paths.
 */
public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLineEntity, UUID> {

    /**
     * Goods-receipt lines for a stock item received under a given vendor lot number
     * (odoo-parity E5, issue #1051): the upstream PO/ASN/goods-receipt origin of a lot.
     */
    List<GoodsReceiptLineEntity> findBySkuAndLotNumber(String sku, String lotNumber);
}
