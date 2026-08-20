package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.service.InventoryAvailabilityService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Benchmark harness for the stock summary read model (issue #1024, A1
 * acceptance: availability P95 &lt; 200 ms with a large ledger). NOT part of
 * the regular suite — {@code @Disabled}; remove the annotation (or run with
 * {@code -Dtest=StockSummaryReadBenchmarkTest} after enabling) to execute
 * manually. Sizing knobs:
 *
 * <ul>
 *   <li>{@code -Dbenchmark.ledger-rows} (default 100000) — ledger entries to seed</li>
 *   <li>{@code -Dbenchmark.skus} (default 200) — distinct stock items</li>
 *   <li>{@code -Dbenchmark.locations} (default 50) — distinct locations</li>
 *   <li>{@code -Dbenchmark.reads} (default 500) — measured availability reads</li>
 * </ul>
 *
 * <p>Note: the test profile runs H2 in-memory; representative numbers require
 * pointing the datasource at a production-shaped PostgreSQL instance.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
@Disabled("benchmark harness — run manually, never in CI")
class StockSummaryReadBenchmarkTest {

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private StockSummaryRebuildService rebuildService;

    @Autowired
    private InventoryAvailabilityService availabilityService;

    @Test
    void measureSummaryReadLatencyOverLargeLedger() {
        int ledgerRows = Integer.getInteger("benchmark.ledger-rows", 100_000);
        int skuCount = Integer.getInteger("benchmark.skus", 200);
        int locationCount = Integer.getInteger("benchmark.locations", 50);
        int reads = Integer.getInteger("benchmark.reads", 500);

        Random random = new Random(42);
        List<UUID> skus = new ArrayList<>(skuCount);
        for (int i = 0; i < skuCount; i++) {
            skus.add(UUID.randomUUID());
        }
        List<UUID> locations = new ArrayList<>(locationCount);
        for (int i = 0; i < locationCount; i++) {
            locations.add(UUID.randomUUID());
        }

        // Seed the ledger directly in batches (bulk load, not the posting
        // funnel), then rebuild the summary from it — mirroring a backfill.
        List<InventoryLedgerEntry> batch = new ArrayList<>(1000);
        for (int i = 0; i < ledgerRows; i++) {
            boolean issue = random.nextInt(4) == 0;
            batch.add(InventoryLedgerEntry.builder()
                    .stockItemId(skus.get(random.nextInt(skuCount)).toString())
                    .locationId(locations.get(random.nextInt(locationCount)))
                    .eventType(issue ? InventoryLedgerEventType.GOODS_ISSUE : InventoryLedgerEventType.GOODS_RECEIPT)
                    .changeInQuantity(BigDecimal.valueOf(issue ? -random.nextInt(3) : 1 + random.nextInt(10)))
                    .quantityAfter(new BigDecimal("0"))
                    .transactionUserId("benchmark")
                    .build());
            if (batch.size() == 1000) {
                ledgerRepository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            ledgerRepository.saveAll(batch);
        }

        long rebuildStart = System.nanoTime();
        int summaryRows = rebuildService.rebuildFromLedger();
        long rebuildMillis = (System.nanoTime() - rebuildStart) / 1_000_000;

        long[] latenciesMicros = new long[reads];
        for (int i = 0; i < reads; i++) {
            UUID sku = skus.get(random.nextInt(skuCount));
            long start = System.nanoTime();
            availabilityService.getAvailabilityByProduct(sku);
            latenciesMicros[i] = (System.nanoTime() - start) / 1_000;
        }
        Arrays.sort(latenciesMicros);
        long p50 = latenciesMicros[reads / 2];
        long p95 = latenciesMicros[(int) (reads * 0.95)];
        long p99 = latenciesMicros[(int) (reads * 0.99)];

        // Plain stdout on purpose: benchmark output, not application logging.
        System.out.printf(
                "stock-summary benchmark: ledgerRows=%d summaryRows=%d rebuildMs=%d readP50us=%d readP95us=%d readP99us=%d%n",
                ledgerRows, summaryRows, rebuildMillis, p50, p95, p99);

        assertThat(summaryRepository.count()).isEqualTo(summaryRows);
        assertThat(p95)
                .as("availability P95 must stay under 200ms (A1 acceptance)")
                .isLessThan(200_000L);
    }
}
