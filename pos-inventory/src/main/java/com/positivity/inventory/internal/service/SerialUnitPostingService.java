package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventorySerialUnit;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.enums.ProductTrackingLevel;
import com.positivity.inventory.internal.exception.SerialAlreadyInStockException;
import com.positivity.inventory.internal.exception.SerialCountMismatchException;
import com.positivity.inventory.internal.exception.SerialNotAvailableException;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Serial enumeration/consumption for the ledger posting funnel (odoo-parity E4, issue #1050;
 * spec §6 E4), invoked in the posting transaction after the summary deltas are applied — the
 * serial analogue of {@link InventoryLotStatusReconciler}.
 *
 * <p>Enforces the serial=quantity invariant for SERIAL-tracked stock items: every on-hand-
 * affecting entry must carry exactly {@code |changeInQuantity|} distinct serial numbers on its
 * transient {@code serialNumbers} carrier, or the posting is rejected with a deterministic 422
 * ({@code SERIAL_COUNT_MISMATCH}) and the whole batch rolls back. The gate mirrors how E1 gates
 * LOT tracking — untracked ({@code NONE}) and {@code LOT}-tracked stock items are never touched
 * here, so their behavior is byte-identical to pre-E4.
 *
 * <ul>
 *   <li>Inbound (change &gt; 0): each serial is enumerated as a new {@code IN_STOCK} unit, or —
 *       for a return/re-receipt of a previously issued serial — the existing unit is flipped back
 *       to {@code IN_STOCK} at the receiving location. Receiving an already-{@code IN_STOCK}
 *       serial is a duplicate and is rejected.</li>
 *   <li>Outbound (change &lt; 0): each named serial must currently be {@code IN_STOCK} (else 422
 *       {@code SERIAL_NOT_AVAILABLE} — this rejects double-issue), and is flipped to
 *       {@code SCRAPPED} for a scrap event or {@code ISSUED} otherwise, recording the consuming
 *       ledger entry and any workorder linkage.</li>
 * </ul>
 *
 * <p>Because this runs inside the funnel's posting transaction, any rejection rolls back the
 * ledger entries and summary rows together — a serialized posting is all-or-nothing.
 */
@Component
@Slf4j
public class SerialUnitPostingService {

    private final InventorySerialUnitRepository serialRepository;
    private final ProductTrackingLevelService trackingLevelService;
    private final Clock clock;

    public SerialUnitPostingService(
            InventorySerialUnitRepository serialRepository,
            ProductTrackingLevelService trackingLevelService,
            Clock clock) {
        this.serialRepository = serialRepository;
        this.trackingLevelService = trackingLevelService;
        this.clock = clock;
    }

    /**
     * Applies serial enumeration/consumption for every SERIAL-tracked, on-hand-affecting entry in
     * the batch, in batch order. Call inside the funnel's posting transaction.
     */
    public void applyPostings(@NonNull List<InventoryLedgerEntry> entries) {
        for (InventoryLedgerEntry entry : entries) {
            InventoryLedgerEventType eventType = entry.getEventType();
            if (eventType == null || !eventType.affectsOnHand()) {
                continue;
            }
            if (trackingLevelService.trackingLevelFor(entry.getStockItemId()) != ProductTrackingLevel.SERIAL) {
                continue;
            }
            apply(entry);
        }
    }

    private void apply(InventoryLedgerEntry entry) {
        int change = entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
        List<String> serials = normalize(entry.getSerialNumbers());
        int required = Math.abs(change);
        if (serials.size() != required) {
            throw new SerialCountMismatchException(entry.getStockItemId(), required, serials.size());
        }
        if (required == 0) {
            return;
        }
        if (change > 0) {
            enumerateInbound(entry, serials);
        } else {
            consumeOutbound(entry, serials);
        }
    }

    private void enumerateInbound(InventoryLedgerEntry entry, List<String> serials) {
        Instant now = Instant.now(clock);
        for (String serialNumber : serials) {
            InventorySerialUnit existing = serialRepository
                    .findByStockItemIdAndSerialNumber(entry.getStockItemId(), serialNumber)
                    .orElse(null);
            if (existing == null) {
                serialRepository.save(InventorySerialUnit.builder()
                        .stockItemId(entry.getStockItemId())
                        .serialNumber(serialNumber)
                        .status(InventorySerialStatus.IN_STOCK)
                        .locationId(entry.getLocationId())
                        .lotId(entry.getLotId())
                        .receiptLedgerEntryId(entry.getLedgerEntryId())
                        .receivedAt(now)
                        .build());
                continue;
            }
            if (existing.getStatus() == InventorySerialStatus.IN_STOCK) {
                throw new SerialAlreadyInStockException(entry.getStockItemId(), serialNumber);
            }
            // Return / re-receipt: an already-known serial re-lands in stock.
            existing.setStatus(InventorySerialStatus.IN_STOCK);
            existing.setLocationId(entry.getLocationId());
            existing.setLotId(entry.getLotId());
            existing.setReceiptLedgerEntryId(entry.getLedgerEntryId());
            existing.setReceivedAt(now);
            existing.setConsumptionLedgerEntryId(null);
            existing.setWorkorderId(null);
            existing.setConsumedAt(null);
            serialRepository.save(existing);
        }
    }

    private void consumeOutbound(InventoryLedgerEntry entry, List<String> serials) {
        Instant now = Instant.now(clock);
        InventorySerialStatus target = entry.getEventType() == InventoryLedgerEventType.SCRAP_OUT
                ? InventorySerialStatus.SCRAPPED
                : InventorySerialStatus.ISSUED;
        for (String serialNumber : serials) {
            InventorySerialUnit unit = serialRepository
                    .findByStockItemIdAndSerialNumber(entry.getStockItemId(), serialNumber)
                    .orElseThrow(() -> new SerialNotAvailableException(entry.getStockItemId(), serialNumber));
            if (unit.getStatus() != InventorySerialStatus.IN_STOCK) {
                throw new SerialNotAvailableException(entry.getStockItemId(), serialNumber);
            }
            unit.setStatus(target);
            // locationId is only meaningful while IN_STOCK; a unit that has left stock has no
            // location. Clearing it keeps the persisted state consistent with the entity contract.
            unit.setLocationId(null);
            unit.setConsumptionLedgerEntryId(entry.getLedgerEntryId());
            unit.setWorkorderId(entry.getSerialWorkorderId());
            unit.setConsumedAt(now);
            serialRepository.save(unit);
        }
    }

    /** Trims, drops blanks, and rejects duplicates (a duplicate is a miscount of physical units). */
    private List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(raw.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (seen.add(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }
}
