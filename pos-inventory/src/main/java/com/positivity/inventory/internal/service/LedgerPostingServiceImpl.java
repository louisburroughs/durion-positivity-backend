package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.NegativeStockPolicy;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>Negative-stock policy (odoo-parity K1, issue #1027): before the summary
 * deltas are applied, the batch is replayed per (stockItemId, locationId) key
 * against the locked summary rows and every on-hand-affecting entry is checked
 * against the {@link NegativeStockPolicy} matrix. A violation aborts the whole
 * posting transaction — ledger entries and summary rows roll back together.
 *
 * <p>Concurrency: the affected summary rows are locked with
 * {@code SELECT ... FOR UPDATE} before validating and applying deltas, in
 * deterministic key order to avoid deadlocks between concurrent batches.
 * First-time row creation happens in a nested transaction
 * ({@link StockSummaryRowInitializer}) so a lost creation race cannot poison
 * the posting transaction.
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
        return postAll(List.of(entry), false).getFirst();
    }

    @Override
    @Transactional
    public @NonNull InventoryLedgerEntry post(@NonNull InventoryLedgerEntry entry, boolean negativeStockOverride) {
        return postAll(List.of(entry), negativeStockOverride).getFirst();
    }

    @Override
    @Transactional
    public @NonNull List<InventoryLedgerEntry> postAll(@NonNull List<InventoryLedgerEntry> entries) {
        return postAll(entries, false);
    }

    @Override
    @Transactional
    public @NonNull List<InventoryLedgerEntry> postAll(
            @NonNull List<InventoryLedgerEntry> entries, boolean negativeStockOverride) {
        List<InventoryLedgerEntry> saved = ledgerRepository.saveAll(entries);

        Map<SummaryKey, SummaryDelta> deltas = new LinkedHashMap<>();
        for (InventoryLedgerEntry entry : saved) {
            SummaryDelta delta = deltaFor(entry);
            if (delta == null) {
                continue;
            }
            deltas.merge(new SummaryKey(entry.getStockItemId(), entry.getLocationId()), delta, SummaryDelta::plus);
        }

        Map<SummaryKey, InventoryStockSummary> lockedRows = new LinkedHashMap<>();
        deltas.keySet().stream()
                .sorted(SummaryKey.ORDER)
                .forEach(key -> lockedRows.put(key, lockRow(key).orElseGet(() -> createAndLockRow(key))));

        enforceNegativeStockPolicy(saved, lockedRows, negativeStockOverride);

        deltas.forEach((key, delta) -> applyDelta(lockedRows.get(key), delta));

        return saved;
    }

    /**
     * Replays the batch per (stockItemId, locationId) key over the locked
     * summary balances and rejects any on-hand-affecting entry whose
     * {@link NegativeStockPolicy} forbids the projected below-zero balance
     * (odoo-parity K1, issue #1027). Enforcement lives here — the single
     * posting path — because a DB-level check is impractical against an
     * append-only ledger.
     */
    private void enforceNegativeStockPolicy(
            List<InventoryLedgerEntry> entries,
            Map<SummaryKey, InventoryStockSummary> lockedRows,
            boolean negativeStockOverride) {
        Map<SummaryKey, Long> projectedOnHand = new HashMap<>();
        for (InventoryLedgerEntry entry : entries) {
            InventoryLedgerEventType eventType = entry.getEventType();
            if (eventType == null || !eventType.affectsOnHand()) {
                continue;
            }
            SummaryKey key = new SummaryKey(entry.getStockItemId(), entry.getLocationId());
            long projected = projectedOnHand.computeIfAbsent(
                    key,
                    missing -> Objects.requireNonNull(
                                    lockedRows.get(missing), "summary row must be locked for on-hand entry " + missing)
                            .getOnHand());
            projected += entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
            projectedOnHand.put(key, projected);
            if (projected < 0) {
                rejectNegativeProjection(eventType, entry, projected, negativeStockOverride);
            }
        }
    }

    private void rejectNegativeProjection(
            InventoryLedgerEventType eventType,
            InventoryLedgerEntry entry,
            long projectedOnHand,
            boolean negativeStockOverride) {
        switch (NegativeStockPolicy.forEventType(eventType)) {
            case BLOCKED ->
                // Keep the pre-K1 error contract for physical outbound flows.
                throw new InsufficientStockException(entry.getStockItemId(), entry.getLocationId());
            case BLOCKED_OVERRIDABLE -> {
                if (!negativeStockOverride) {
                    throw new NegativeStockPolicyViolationException(
                            NegativeStockPolicyViolationException.OVERRIDE_REQUIRED,
                            eventType + " for stock item " + entry.getStockItemId() + " at location "
                                    + entry.getLocationId() + " would take on-hand to " + projectedOnHand
                                    + "; requires inventory:adjustment:override");
                }
                log.warn(
                        "Negative-stock override accepted: eventType={} stockItemId={} locationId={} projectedOnHand={}",
                        eventType,
                        entry.getStockItemId(),
                        entry.getLocationId(),
                        projectedOnHand);
            }
            case FLOOR_AT_ZERO ->
                throw new NegativeStockPolicyViolationException(
                        NegativeStockPolicyViolationException.FLOOR_VIOLATION,
                        eventType + " for stock item " + entry.getStockItemId() + " at location "
                                + entry.getLocationId() + " would take on-hand to " + projectedOnHand
                                + "; counts and adjustments may zero stock but never drive it negative");
            case UNCONSTRAINED -> {
                // Inbound/paired-move types post freely.
            }
        }
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

    private void applyDelta(InventoryStockSummary row, SummaryDelta delta) {
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
