package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInventoryGoodsReceipt;
import com.positivity.accounting.internal.repository.ExtInventoryGoodsReceiptRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The replica of pos-inventory's goods receipts (#2750; ADR-0044 R3), read for the deliveries no approved bill covers.
 * Written only by {@link InventoryEventsListener}, in the handler transaction that writes the ingestion record and
 * marks the fact processed, and by {@link GoodsReceiptReplicaBackfill}, which replays the stored facts through this
 * same writer.
 *
 * <p>The fact's envelope aggregate version is always 0, so the copy is not version-guarded; it is keyed on the
 * receipt id. A redelivered or corrected re-emit of the same receipt updates the receipt number, order, amount and
 * currency, and keeps {@code received_at}: the first fact's date is the delivery date.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsReceiptReplica {

    private final ExtInventoryGoodsReceiptRepository receipts;

    /** Upserts the receipt, keeping the first fact's received time; joins the caller's transaction. */
    @Transactional
    public void recorded(@NonNull GoodsReceiptRecordedV1 fact) {
        ExtInventoryGoodsReceipt row = receipts.findById(fact.receiptId()).orElse(null);
        if (row == null) {
            row = new ExtInventoryGoodsReceipt();
            row.setGoodsReceiptId(fact.receiptId());
            row.setReceivedAt(fact.occurredAt());
        } else {
            log.debug("Goods receipt re-emitted, updating its copy receiptId={}", fact.receiptId());
        }
        row.setReceiptNumber(fact.receiptNumber());
        row.setPurchaseOrderId(fact.purchaseOrderId());
        row.setAccruedAmountMinor(fact.totalAccruedAmountMinor());
        row.setCurrencyCode(fact.currencyCode());
        receipts.save(row);
    }
}
