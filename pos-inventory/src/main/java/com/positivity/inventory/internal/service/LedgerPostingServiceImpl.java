package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.NegativeStockPolicy;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.LotInsufficientStockException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
 *   <li>{@code TRANSFER_OUT} with a {@code toLocationId} — additionally {@code +qty} to the
 *       DESTINATION key's {@code inTransitQty}; {@code TRANSFER_IN} — {@code -qty} from its own
 *       key's {@code inTransitQty} (odoo-parity C2, issue #1036)</li>
 *   <li>anything else (backorders, pick-task markers) — no summary touch</li>
 * </ul>
 * {@code atp} is maintained as {@code onHand - allocated} (ADR-0001).
 *
 * <p>Dual-row lot bookkeeping (odoo-parity E1, issue #1038): every entry applies its deltas to
 * the lot-agnostic (stockItemId, locationId, lotId=null) row exactly as before E1 — that row
 * stays the authoritative balance for all availability/rollup/forecast readers and for the
 * negative-stock matrix. An entry carrying a {@code lotId} ADDITIONALLY applies the same deltas
 * to the (stockItemId, locationId, lotId) row, from which the lot read API serves per-lot
 * on-hand. Rebuild and drift verification replay the identical rule.
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
    private final ExtProductReplicaRepository extProductReplicaRepository;
    private final InventoryLotStatusReconciler lotStatusReconciler;
    private final SerialUnitPostingService serialUnitPostingService;
    private final LedgerCostingService costingService;

    /**
     * Availability-driven backorder resolver (odoo-parity G1, issue #1046). Held as an
     * {@link ObjectProvider} so the funnel does not form an eager dependency cycle with the resolver
     * (which posts {@code BACKORDER_RESOLVED} back through this funnel). The provider is empty in the
     * pre-G1 unit fixtures that construct this service directly.
     */
    private final ObjectProvider<BackorderResolutionTrigger> backorderResolutionTrigger;

    public LedgerPostingServiceImpl(
            InventoryLedgerEntryRepository ledgerRepository,
            InventoryStockSummaryRepository summaryRepository,
            StockSummaryRowInitializer rowInitializer,
            ExtProductReplicaRepository extProductReplicaRepository,
            InventoryLotStatusReconciler lotStatusReconciler,
            SerialUnitPostingService serialUnitPostingService,
            LedgerCostingService costingService,
            ObjectProvider<BackorderResolutionTrigger> backorderResolutionTrigger) {
        this.ledgerRepository = ledgerRepository;
        this.summaryRepository = summaryRepository;
        this.rowInitializer = rowInitializer;
        this.extProductReplicaRepository = extProductReplicaRepository;
        this.lotStatusReconciler = lotStatusReconciler;
        this.serialUnitPostingService = serialUnitPostingService;
        this.costingService = costingService;
        this.backorderResolutionTrigger = backorderResolutionTrigger;
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
        entries.forEach(this::validateUnitOfMeasure);
        List<InventoryLedgerEntry> saved = ledgerRepository.saveAll(entries);

        Map<SummaryKey, SummaryDelta> deltas = new LinkedHashMap<>();
        for (InventoryLedgerEntry entry : saved) {
            SummaryDelta delta = deltaFor(entry);
            if (delta != null) {
                // Lot-agnostic row first (pre-E1 behavior), then the additional per-lot row for
                // lot-tagged entries — same deltas on both (odoo-parity E1, #1038).
                deltas.merge(
                        new SummaryKey(entry.getStockItemId(), entry.getLocationId(), null), delta, SummaryDelta::plus);
                if (entry.getLotId() != null) {
                    deltas.merge(
                            new SummaryKey(entry.getStockItemId(), entry.getLocationId(), entry.getLotId()),
                            delta,
                            SummaryDelta::plus);
                }
            }
            SummaryDelta transitDelta = outboundInTransitDeltaFor(entry);
            if (transitDelta != null) {
                deltas.merge(
                        new SummaryKey(entry.getStockItemId(), entry.getToLocationId(), null),
                        transitDelta,
                        SummaryDelta::plus);
                if (entry.getLotId() != null) {
                    deltas.merge(
                            new SummaryKey(entry.getStockItemId(), entry.getToLocationId(), entry.getLotId()),
                            transitDelta,
                            SummaryDelta::plus);
                }
            }
        }

        Map<SummaryKey, InventoryStockSummary> lockedRows = new LinkedHashMap<>();
        deltas.keySet().stream()
                .sorted(SummaryKey.ORDER)
                .forEach(key -> lockedRows.put(key, lockRow(key).orElseGet(() -> createAndLockRow(key))));

        enforceNegativeStockPolicy(saved, lockedRows, negativeStockOverride);
        enforcePerLotFloor(saved, lockedRows);

        deltas.forEach((key, delta) -> applyDelta(lockedRows.get(key), delta));

        // odoo-parity J1 (#1048, ADR-0048): stamp a method-derived unitCost on every
        // on-hand-affecting entry and update each SKU's running cost state, in batch order,
        // in this same transaction. A receipt preceding an issue for the same SKU updates the
        // running cost first, then the issue stamps it. Non-on-hand-affecting entries are skipped
        // (no cost), so the re-entrant BACKORDER_RESOLVED post below collects none.
        costingService.stampCosts(saved);

        // odoo-parity E2 (#1042): after the per-lot rows are current, reconcile the
        // ACTIVE <-> CONSUMED status of every lot this batch moved stock for.
        Set<UUID> touchedLotIds = new LinkedHashSet<>();
        for (InventoryLedgerEntry entry : saved) {
            if (entry.getLotId() != null
                    && entry.getEventType() != null
                    && entry.getEventType().affectsOnHand()) {
                touchedLotIds.add(entry.getLotId());
            }
        }
        if (!touchedLotIds.isEmpty()) {
            lotStatusReconciler.reconcile(touchedLotIds);
        }

        // odoo-parity E4 (#1050): enforce the serial=quantity invariant and enumerate/consume the
        // named serial units for every SERIAL-tracked, on-hand-affecting entry in this batch. A
        // count mismatch or a double-issue rolls back the whole posting (this same transaction).
        // Untracked and LOT-tracked stock items are never touched — byte-identical to pre-E4.
        serialUnitPostingService.applyPostings(saved);

        triggerBackorderResolution(saved);

        return saved;
    }

    /**
     * After the summary deltas are applied, attempt availability-driven backorder resolution for
     * every (SKU, site) this batch raised on-hand at (odoo-parity G1, issue #1046). "Raised on-hand"
     * = an on-hand-affecting entry with a positive change (goods receipt, transfer-in, adjustment-in,
     * count-variance-in, put-away landing, return-to-stock). The resolver posts only the ATP-neutral
     * {@code BACKORDER_RESOLVED} entry — not on-hand-affecting — so this re-entrant post collects no
     * inbound keys and the loop is closed by construction. Best-effort: a resolver failure is logged
     * and never rolls back the inbound posting.
     */
    private void triggerBackorderResolution(List<InventoryLedgerEntry> entries) {
        BackorderResolutionTrigger trigger = backorderResolutionTrigger.getIfAvailable();
        if (trigger == null) {
            return;
        }
        Set<SummaryKey> inboundKeys = new LinkedHashSet<>();
        for (InventoryLedgerEntry entry : entries) {
            InventoryLedgerEventType eventType = entry.getEventType();
            int change = entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
            if (eventType != null
                    && eventType.affectsOnHand()
                    && change > 0
                    && entry.getStockItemId() != null
                    && entry.getLocationId() != null) {
                inboundKeys.add(new SummaryKey(entry.getStockItemId(), entry.getLocationId(), null));
            }
        }
        for (SummaryKey key : inboundKeys) {
            try {
                trigger.onInboundAvailability(key.stockItemId(), key.locationId());
            } catch (RuntimeException e) {
                log.warn(
                        "Backorder auto-resolution failed for stockItemId={} locationId={}: {}",
                        key.stockItemId(),
                        key.locationId(),
                        e.getMessage());
            }
        }
    }

    /**
     * Validates an entry's free-text {@code unitOfMeasure} against the catalog replica
     * (odoo-parity B2/B4, #1034): the ledger is base-UoM only, so an entry naming a UoM that
     * contradicts the product's replica base UoM is rejected with a deterministic 422
     * ({@code UOM_CONVERSION_UNDEFINED}).
     *
     * <p>Documented tolerance — validation only applies "going forward" where the catalog
     * contract can actually answer; each of these passes through unvalidated rather than
     * hard-failing products that predate the contract:
     * <ul>
     *   <li>{@code unitOfMeasure} absent/blank (most posting paths never set it)</li>
     *   <li>{@code stockItemId} is not a UUID (legacy free-text SKUs)</li>
     *   <li>no {@code ext_product} replica row for the product</li>
     *   <li>replica row exists but carries no base UoM (pre-v2 catalog facts)</li>
     * </ul>
     * The comparison is case-insensitive.
     */
    private void validateUnitOfMeasure(InventoryLedgerEntry entry) {
        String unitOfMeasure = entry.getUnitOfMeasure();
        if (unitOfMeasure == null || unitOfMeasure.isBlank()) {
            return;
        }
        UUID productId = parseProductId(entry.getStockItemId());
        if (productId == null) {
            return;
        }
        extProductReplicaRepository.findById(productId).ifPresent(replica -> {
            String baseUom = replica.getBaseUom();
            if (baseUom != null && !baseUom.equalsIgnoreCase(unitOfMeasure.trim())) {
                throw UomConversionUndefinedException.nonBaseLedgerUom(productId, unitOfMeasure, baseUom);
            }
        });
    }

    private @Nullable UUID parseProductId(@Nullable String stockItemId) {
        if (stockItemId == null || stockItemId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(stockItemId.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
            // The matrix evaluates the lot-agnostic key only; the per-lot floor is a separate,
            // absolute check (enforcePerLotFloor — odoo-parity E2, #1042).
            SummaryKey key = new SummaryKey(entry.getStockItemId(), entry.getLocationId(), null);
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

    /**
     * Per-lot negative floor (odoo-parity E2, issue #1042; spec §6 E2 — "negative lot balances
     * rejected"): replays the batch's lot-tagged on-hand-affecting entries over the locked
     * per-lot rows and rejects any entry whose projected per-lot on-hand goes below zero
     * (422 {@code LOT_INSUFFICIENT_STOCK}).
     *
     * <p>Unlike the lot-agnostic matrix above, the floor is absolute — no per-type policy, no
     * override: a physical lot can never hold negative stock, and letting it would silently
     * misattribute stock between lots (the whole point of lot tracking). The lot-agnostic
     * matrix stays exactly as it was — the two checks are independent, and both must pass.
     * Batch order is preserved, so a constructive {@code TRANSFER_IN} preceding its paired
     * outbound entry (the C3 short-close shapes) projects correctly.
     */
    private void enforcePerLotFloor(
            List<InventoryLedgerEntry> entries, Map<SummaryKey, InventoryStockSummary> lockedRows) {
        Map<SummaryKey, Long> projectedOnHand = new HashMap<>();
        for (InventoryLedgerEntry entry : entries) {
            InventoryLedgerEventType eventType = entry.getEventType();
            if (entry.getLotId() == null || eventType == null || !eventType.affectsOnHand()) {
                continue;
            }
            SummaryKey key = new SummaryKey(entry.getStockItemId(), entry.getLocationId(), entry.getLotId());
            long projected = projectedOnHand.computeIfAbsent(
                    key,
                    missing -> Objects.requireNonNull(
                                    lockedRows.get(missing), "per-lot summary row must be locked for entry " + missing)
                            .getOnHand());
            projected += entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
            projectedOnHand.put(key, projected);
            if (projected < 0) {
                throw new LotInsufficientStockException(
                        entry.getStockItemId(), entry.getLotId(), entry.getLocationId(), projected);
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
        long inTransit = 0;
        if (eventType != null && eventType.affectsOnHand()) {
            onHand = change;
            if (eventType == InventoryLedgerEventType.TRANSFER_IN) {
                // Arrival at the destination key: goods leave transit as they land on-hand
                // (odoo-parity C2, #1036). -change because arrivals carry a positive change.
                inTransit = -change;
            }
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
        return new SummaryDelta(onHand, allocated, reserved, inTransit, entry.getLedgerEntryId(), entry.getTimestamp());
    }

    /**
     * In-transit contribution of a {@code TRANSFER_OUT} entry at the DESTINATION key
     * (odoo-parity C2, issue #1036): goods that left the source ({@code locationId}) are
     * inbound to {@code toLocationId} until a matching {@code TRANSFER_IN} lands there.
     * {@code -change} because departures carry a negative change. Ledger-shape rule, no
     * document lookup: intra-site bin moves post OUT+IN in one transaction, so their
     * contributions cancel and only transfer orders (receive lags dispatch) leave a residual.
     */
    private @Nullable SummaryDelta outboundInTransitDeltaFor(InventoryLedgerEntry entry) {
        if (entry.getEventType() != InventoryLedgerEventType.TRANSFER_OUT || entry.getToLocationId() == null) {
            return null;
        }
        int change = entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
        return new SummaryDelta(0, 0, 0, -change, entry.getLedgerEntryId(), entry.getTimestamp());
    }

    private void applyDelta(InventoryStockSummary row, SummaryDelta delta) {
        row.setOnHand(row.getOnHand() + delta.onHand());
        row.setAllocated(row.getAllocated() + delta.allocated());
        row.setReserved(row.getReserved() + delta.reserved());
        row.setAtp(row.getOnHand() - row.getAllocated());
        row.setInTransitQty(row.getInTransitQty() + delta.inTransit());
        row.setLastLedgerEntryId(delta.lastLedgerEntryId());
        row.setLastEventAt(delta.lastEventAt());
        summaryRepository.save(row);
    }

    /**
     * Serializes summary-row creation in-JVM: since E1 the lot-agnostic row of every key holds a
     * NULL {@code lot_id}, which only PostgreSQL's {@code NULLS NOT DISTINCT} unique index (V18)
     * can reject as a duplicate — the JPA-generated H2 test schema cannot, so the constraint
     * alone no longer closes the check-then-insert race locally. The monitor covers the
     * initializer's whole REQUIRES_NEW transaction (the call goes through the proxy); across
     * instances the PostgreSQL index remains the backstop.
     */
    private static final Object ROW_CREATION_MONITOR = new Object();

    private InventoryStockSummary createAndLockRow(SummaryKey key) {
        try {
            synchronized (ROW_CREATION_MONITOR) {
                rowInitializer.createRowIfAbsent(key.stockItemId(), key.locationId(), key.lotId());
            }
        } catch (DataIntegrityViolationException | UnexpectedRollbackException ex) {
            // Lost the creation race to a concurrent posting; the row exists now.
            log.debug(
                    "Lost stock summary creation race for stockItemId={} locationId={} lotId={}",
                    key.stockItemId(),
                    key.locationId(),
                    key.lotId());
        }
        return lockRow(key)
                .orElseThrow(() ->
                        new IllegalStateException("Stock summary row missing after initialization for stockItemId="
                                + key.stockItemId() + " locationId=" + key.locationId() + " lotId=" + key.lotId()));
    }

    private Optional<InventoryStockSummary> lockRow(SummaryKey key) {
        return summaryRepository.findWithLockByKey(key.stockItemId(), key.locationId(), key.lotId());
    }

    private record SummaryKey(
            String stockItemId,
            @Nullable UUID locationId,
            @Nullable UUID lotId) {

        /** Deterministic lock-acquisition order to avoid deadlocks between concurrent batches. */
        private static final Comparator<SummaryKey> ORDER = Comparator.comparing(SummaryKey::stockItemId)
                .thenComparing(
                        key -> key.locationId() == null ? "" : key.locationId().toString())
                .thenComparing(key -> key.lotId() == null ? "" : key.lotId().toString());
    }

    private record SummaryDelta(
            long onHand,
            long allocated,
            long reserved,
            long inTransit,
            @Nullable UUID lastLedgerEntryId,
            @Nullable Instant lastEventAt) {

        private SummaryDelta plus(SummaryDelta other) {
            return new SummaryDelta(
                    onHand + other.onHand,
                    allocated + other.allocated,
                    reserved + other.reserved,
                    inTransit + other.inTransit,
                    other.lastLedgerEntryId() != null ? other.lastLedgerEntryId() : lastLedgerEntryId,
                    other.lastEventAt() != null ? other.lastEventAt() : lastEventAt);
        }
    }
}
