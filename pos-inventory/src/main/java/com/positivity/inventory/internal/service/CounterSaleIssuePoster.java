package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts one counter-sale GOODS_ISSUE movement in its own transaction (order parity story H2,
 * #1079). {@code REQUIRES_NEW} isolates each line: a rejected post (insufficient stock, unknown
 * stock item) rolls back alone instead of poisoning the consumer's transaction — the listener
 * converts the failure into an alert fact and the completed sale is never affected (spec R8.2).
 *
 * <p>Per-line idempotency: because a listener retry after partial success would replay already
 * committed REQUIRES_NEW postings, each post first checks the ledger for an entry with the same
 * event type and {@code sourceTransactionId} (the order line id) and skips duplicates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterSaleIssuePoster {

    private final LedgerPostingService ledgerPostingService;
    private final InventoryLedgerEntryRepository ledgerEntryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void post(@NonNull InventoryLedgerEntry entry) {
        if (entry.getSourceTransactionId() != null
                && ledgerEntryRepository.existsByEventTypeAndSourceTransactionId(
                        entry.getEventType(), entry.getSourceTransactionId())) {
            log.debug(
                    "Skipping duplicate counter-sale issue for sourceTransactionId={}", entry.getSourceTransactionId());
            return;
        }
        ledgerPostingService.post(entry);
    }
}
