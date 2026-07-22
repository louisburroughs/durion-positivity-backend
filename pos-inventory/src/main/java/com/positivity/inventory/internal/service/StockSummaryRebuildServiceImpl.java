package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds {@code inventory_stock_summary} from the ledger: one grouped
 * aggregation over all summary-relevant entries, then a per-key lookup of the
 * latest contributing entry to restore {@code lastLedgerEntryId}/{@code
 * lastEventAt}. Applies the same delta rules as {@code LedgerPostingServiceImpl}
 * so a rebuild reproduces exactly what live posting maintains.
 */
@Service
@Slf4j
public class StockSummaryRebuildServiceImpl implements StockSummaryRebuildService {

    private static final Pageable LATEST_ONLY = PageRequest.of(0, 1);

    private final InventoryLedgerEntryRepository ledgerRepository;
    private final InventoryStockSummaryRepository summaryRepository;

    public StockSummaryRebuildServiceImpl(
            InventoryLedgerEntryRepository ledgerRepository, InventoryStockSummaryRepository summaryRepository) {
        this.ledgerRepository = ledgerRepository;
        this.summaryRepository = summaryRepository;
    }

    @Override
    @Transactional
    public int rebuildFromLedger() {
        summaryRepository.deleteAllInBatch();

        List<InventoryLedgerEntryRepository.SummaryAggregate> aggregates = ledgerRepository.aggregateForStockSummary(
                InventoryLedgerEventType.onHandAffectingTypes(),
                InventoryLedgerEventType.ALLOCATION_CREATED,
                InventoryLedgerEventType.ALLOCATION_RELEASED,
                InventoryLedgerEventType.RESERVATION_CREATED,
                InventoryLedgerEventType.RESERVATION_RELEASED,
                StockSummaryEventSets.SUMMARY_TYPES);

        List<InventoryStockSummary> rows =
                aggregates.stream().map(this::toSummaryRow).toList();
        summaryRepository.saveAll(rows);

        log.info("Rebuilt inventory_stock_summary from ledger: {} rows", rows.size());
        return rows.size();
    }

    private InventoryStockSummary toSummaryRow(InventoryLedgerEntryRepository.SummaryAggregate aggregate) {
        InventoryLedgerEntry latest = ledgerRepository
                .findLatestSummaryEntries(
                        aggregate.getStockItemId(),
                        aggregate.getLocationId(),
                        StockSummaryEventSets.SUMMARY_TYPES,
                        LATEST_ONLY)
                .stream()
                .findFirst()
                .orElse(null);

        return InventoryStockSummary.builder()
                .stockItemId(aggregate.getStockItemId())
                .locationId(aggregate.getLocationId())
                .onHand(aggregate.getOnHand())
                .allocated(aggregate.getAllocated())
                .reserved(aggregate.getReserved())
                .atp(aggregate.getOnHand() - aggregate.getAllocated())
                .lastLedgerEntryId(latest == null ? null : latest.getLedgerEntryId())
                .lastEventAt(latest == null ? null : latest.getTimestamp())
                .build();
    }
}
