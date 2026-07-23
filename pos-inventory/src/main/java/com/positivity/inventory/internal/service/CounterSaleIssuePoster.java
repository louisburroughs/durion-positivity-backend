package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts one counter-sale GOODS_ISSUE movement in its own transaction (order parity story H2,
 * #1079). {@code REQUIRES_NEW} isolates each line: a rejected post (insufficient stock, unknown
 * stock item) rolls back alone instead of poisoning the consumer's transaction — the listener
 * converts the failure into an alert fact and the completed sale is never affected (spec R8.2).
 */
@Component
@RequiredArgsConstructor
public class CounterSaleIssuePoster {

    private final LedgerPostingService ledgerPostingService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void post(@NonNull InventoryLedgerEntry entry) {
        ledgerPostingService.post(entry);
    }
}
