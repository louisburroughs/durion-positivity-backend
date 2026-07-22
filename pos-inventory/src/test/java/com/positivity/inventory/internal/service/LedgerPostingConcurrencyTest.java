package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Concurrency IT for the stock summary upsert (issue #1024, A1 acceptance):
 * parallel postings to the same (SKU, location) key must not lose updates —
 * the final summary must equal {@code SUM(ledger)}. The row-level
 * {@code SELECT ... FOR UPDATE} in {@code LedgerPostingServiceImpl}
 * serializes the read-modify-write.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LedgerPostingService concurrency — no lost summary updates")
class LedgerPostingConcurrencyTest {

    private static final int THREADS = 6;
    private static final int POSTS_PER_THREAD = 20;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Test
    void concurrentPostingsToSameKey_finalSummaryEqualsLedgerSum() throws Exception {
        String sku = "SKU-CONC-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Void>> workers = new ArrayList<>();
            for (int workerIndex = 0; workerIndex < THREADS; workerIndex++) {
                int delta = workerIndex + 1;
                workers.add(() -> {
                    startGate.await();
                    for (int post = 0; post < POSTS_PER_THREAD; post++) {
                        InventoryLedgerEventType type = post % 3 == 2
                                ? InventoryLedgerEventType.GOODS_ISSUE
                                : InventoryLedgerEventType.GOODS_RECEIPT;
                        int change = type == InventoryLedgerEventType.GOODS_ISSUE ? -delta : delta;
                        ledgerPostingService.post(InventoryLedgerEntry.builder()
                                .stockItemId(sku)
                                .locationId(location)
                                .eventType(type)
                                .changeInQuantity(change)
                                .quantityAfter(0)
                                .transactionUserId("concurrency-test")
                                .build());
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = workers.stream().map(executor::submit).toList();
            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        long ledgerSum = ledgerRepository
                .calculateOnHandQuantityAtLocation(sku, location)
                .longValue();
        InventoryStockSummary summary =
                summaryRepository.findByStockItemIdAndLocationId(sku, location).orElseThrow();

        assertThat(summary.getOnHand())
                .as("summary on-hand must equal SUM(ledger) — no lost updates")
                .isEqualTo(ledgerSum);
        // Exactly one summary row despite the first-post creation race.
        assertThat(summaryRepository.findByStockItemId(sku)).hasSize(1);
    }
}
