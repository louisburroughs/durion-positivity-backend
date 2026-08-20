package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * CONSUMED status transition for lots (odoo-parity E2, issue #1042; spec §6 E2): invoked by
 * the ledger posting funnel — in the posting transaction, after the summary deltas are
 * applied — for every lot an on-hand-affecting entry touched.
 *
 * <p>Rule (exact-zero, in-transaction): a lot whose total attributed stock across ALL per-lot
 * summary rows — on-hand everywhere plus in-transit toward anywhere, so dispatched-but-
 * unreceived transfers keep counting — reaches exactly zero flips {@code ACTIVE → CONSUMED};
 * a lot that regains stock (e.g. a return re-increments it) flips {@code CONSUMED → ACTIVE}.
 * {@code QUARANTINED}/{@code RECALLED} are operator-owned states and are never touched here.
 *
 * <p>Race tolerance: the posting transaction holds row locks on the per-lot rows it changed,
 * but not on the lot's rows at other locations. Two concurrent postings against different
 * locations of the same lot can therefore each compute a total the other is about to change —
 * whichever commits last wins, and since every posting that moves lot stock re-runs this
 * reconciliation, the status converges to the correct value with the next posting. The status
 * is a derived convenience flag (per-lot balances remain the source of truth), so a transient
 * stale value between postings is acceptable by design; the summary rebuild path does not
 * touch lot statuses for the same reason.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryLotStatusReconciler {

    private final InventoryLotRepository lotRepository;
    private final InventoryStockSummaryRepository summaryRepository;

    /** Reconciles ACTIVE/CONSUMED for each lot; call inside the posting transaction. */
    public void reconcile(@NonNull Collection<UUID> lotIds) {
        for (UUID lotId : lotIds) {
            InventoryLot lot = lotRepository.findById(lotId).orElse(null);
            if (lot == null) {
                continue;
            }
            BigDecimal totalStock = Quantities.nz(summaryRepository.sumLotStockAcrossLocations(lotId));
            if (Quantities.isZero(totalStock) && lot.getStatus() == InventoryLotStatus.ACTIVE) {
                lot.setStatus(InventoryLotStatus.CONSUMED);
                lotRepository.save(lot);
                log.info("Lot {} ({}) fully consumed; status ACTIVE -> CONSUMED", lotId, lot.getLotNumber());
            } else if (Quantities.isPositive(totalStock) && lot.getStatus() == InventoryLotStatus.CONSUMED) {
                lot.setStatus(InventoryLotStatus.ACTIVE);
                lotRepository.save(lot);
                log.info(
                        "Lot {} ({}) regained stock ({}); status CONSUMED -> ACTIVE",
                        lotId,
                        lot.getLotNumber(),
                        totalStock);
            }
        }
    }
}
