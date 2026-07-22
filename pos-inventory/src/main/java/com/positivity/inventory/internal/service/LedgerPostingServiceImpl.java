package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single ledger posting path (issue #1024, A1): appends entries and applies
 * their deltas to {@code inventory_stock_summary} in the same transaction.
 *
 * <p>Summary impact per event type:
 * <ul>
 *   <li>{@link InventoryLedgerEventType#affectsOnHand() on-hand-affecting} — {@code onHand}</li>
 *   <li>{@code ALLOCATION_CREATED}/{@code ALLOCATION_RELEASED} — {@code allocated}</li>
 *   <li>{@code RESERVATION_CREATED}/{@code RESERVATION_RELEASED} — {@code reserved}</li>
 *   <li>anything else (backorders, pick-task markers) — no summary touch</li>
 * </ul>
 * {@code atp} is maintained as {@code onHand - allocated} (ADR-0001).
 *
 * <p>Concurrency: the affected summary rows are locked with
 * {@code SELECT ... FOR UPDATE} before applying deltas, in deterministic key
 * order to avoid deadlocks between concurrent batches. First-time row
 * creation happens in a nested transaction ({@link StockSummaryRowInitializer})
 * so a lost creation race cannot poison the posting transaction.
 */
@Service
@Slf4j
public class LedgerPostingServiceImpl implements LedgerPostingService {

    private final InventoryLedgerEntryRepository ledgerRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final StockSummaryRowInitializer rowInitializer;

    public LedgerPostingServiceImpl(
            InventoryLedgerEntryRepository ledgerRepository,
            InventoryStockSummaryRepository summaryRepository,
            StockSummaryRowInitializer rowInitializer) {
        this.ledgerRepository = ledgerRepository;
        this.summaryRepository = summaryRepository;
        this.rowInitializer = rowInitializer;
    }

    @Override
    @Transactional
    public @NonNull InventoryLedgerEntry post(@NonNull InventoryLedgerEntry entry) {
        return postAll(List.of(entry)).getFirst();
    }

    @Override
    @Transactional
    public @NonNull List<InventoryLedgerEntry> postAll(@NonNull List<InventoryLedgerEntry> entries) {
        List<InventoryLedgerEntry> saved = ledgerRepository.saveAll(entries);

        Map<SummaryKey, SummaryDelta> deltas = new LinkedHashMap<>();
        for (InventoryLedgerEntry entry : saved) {
            SummaryDelta delta = deltaFor(entry);
            if (delta == null) {
                continue;
            }
            deltas.merge(new SummaryKey(entry.getStockItemId(), entry.getLocationId()), delta, SummaryDelta::plus);
        }

        deltas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(SummaryKey.ORDER))
                .forEach(keyed -> applyDelta(keyed.getKey(), keyed.getValue()));

        return saved;
    }

    private @Nullable SummaryDelta deltaFor(InventoryLedgerEntry entry) {
        InventoryLedgerEventType eventType = entry.getEventType();
        int change = entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
        long onHand = 0;
        long allocated = 0;
        long reserved = 0;
        if (eventType != null && eventType.affectsOnHand()) {
            onHand = change;
        } else if (eventType == InventoryLedgerEventType.ALLOCATION_CREATED) {
            allocated = change;
        } else if (eventType == InventoryLedgerEventType.ALLOCATION_RELEASED) {
            allocated = -change;
        } else if (eventType == InventoryLedgerEventType.RESERVATION_CREATED) {
            reserved = change;
        } else if (eventType == InventoryLedgerEventType.RESERVATION_RELEASED) {
            reserved = -change;
        } else {
            return null;
        }
        return new SummaryDelta(onHand, allocated, reserved, entry.getLedgerEntryId(), entry.getTimestamp());
    }

    private void applyDelta(SummaryKey key, SummaryDelta delta) {
        InventoryStockSummary row = lockRow(key).orElseGet(() -> createAndLockRow(key));
        row.setOnHand(row.getOnHand() + delta.onHand());
        row.setAllocated(row.getAllocated() + delta.allocated());
        row.setReserved(row.getReserved() + delta.reserved());
        row.setAtp(row.getOnHand() - row.getAllocated());
        row.setLastLedgerEntryId(delta.lastLedgerEntryId());
        row.setLastEventAt(delta.lastEventAt());
        summaryRepository.save(row);
    }

    private InventoryStockSummary createAndLockRow(SummaryKey key) {
        try {
            rowInitializer.createRowIfAbsent(key.stockItemId(), key.locationId());
        } catch (DataIntegrityViolationException | UnexpectedRollbackException ex) {
            // Lost the creation race to a concurrent posting; the row exists now.
            log.debug(
                    "Lost stock summary creation race for stockItemId={} locationId={}",
                    key.stockItemId(),
                    key.locationId());
        }
        return lockRow(key)
                .orElseThrow(() ->
                        new IllegalStateException("Stock summary row missing after initialization for stockItemId="
                                + key.stockItemId() + " locationId=" + key.locationId()));
    }

    private Optional<InventoryStockSummary> lockRow(SummaryKey key) {
        return key.locationId() == null
                ? summaryRepository.findWithLockByStockItemIdAndLocationIdIsNull(key.stockItemId())
                : summaryRepository.findWithLockByStockItemIdAndLocationId(key.stockItemId(), key.locationId());
    }

    private record SummaryKey(String stockItemId, @Nullable UUID locationId) {

        /** Deterministic lock-acquisition order to avoid deadlocks between concurrent batches. */
        private static final Comparator<SummaryKey> ORDER = Comparator.comparing(SummaryKey::stockItemId)
                .thenComparing(
                        key -> key.locationId() == null ? "" : key.locationId().toString());
    }

    private record SummaryDelta(
            long onHand,
            long allocated,
            long reserved,
            @Nullable UUID lastLedgerEntryId,
            @Nullable Instant lastEventAt) {

        private SummaryDelta plus(SummaryDelta other) {
            return new SummaryDelta(
                    onHand + other.onHand,
                    allocated + other.allocated,
                    reserved + other.reserved,
                    other.lastLedgerEntryId() != null ? other.lastLedgerEntryId() : lastLedgerEntryId,
                    other.lastEventAt() != null ? other.lastEventAt() : lastEventAt);
        }
    }
}
