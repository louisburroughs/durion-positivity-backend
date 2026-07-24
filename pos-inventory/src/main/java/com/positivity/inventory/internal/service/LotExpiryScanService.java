package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.LotExpiryAlertV1;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.LotExpiryAlertKind;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily lot-expiry scan (odoo-parity E3, issue #1047; spec §6 E3, decision D-7). Finds the ACTIVE
 * lots that newly expired ({@code expirationDate < today}) or just entered their alert window
 * ({@code alertDate <= today <= expirationDate}) and emits one {@link LotExpiryAlertV1} per lot per
 * transition.
 *
 * <p>Expired lots are the eventual driver of ATP exclusion; the availability read path also
 * subtracts expired-lot on-hand from ATP on read (the D-7 dual guard, in
 * {@code InventoryAvailabilityServiceImpl}), so a lot drops from ATP the moment it expires, not
 * only after this scan runs. This scan's job is to raise the alert facts.
 *
 * <p>Idempotency (emit-once): each lot carries {@code expiredAlertedAt}/{@code expiringAlertedAt}
 * stamps written in the same transaction the fact is queued. A re-run only selects lots whose
 * stamp is still null, so an already-alerted transition is never re-emitted. The transaction wraps
 * both the stamp write and the fact-outbox enqueue, so the fact and its bookkeeping commit or roll
 * back together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LotExpiryScanService {

    private final InventoryLotRepository lotRepository;
    private final InventoryFactPublisher factPublisher;
    private final Clock clock;

    /** Outcome of one scan pass. */
    public record LotExpiryScanResult(int expiredAlerted, int expiringAlerted) {}

    /**
     * Runs one scan pass. Newly-expired lots are alerted first, then lots entering the alert
     * window; a lot that both entered its window and expired since the last run raises only the
     * EXPIRED alert (the window query excludes already-expired lots).
     */
    @Transactional
    public @NonNull LotExpiryScanResult runExpiryScan() {
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);

        List<InventoryLot> expired = lotRepository.findNewlyExpired(InventoryLotStatus.ACTIVE, today);
        for (InventoryLot lot : expired) {
            lot.setExpiredAlertedAt(now);
            factPublisher.recordLotExpiryAlert(alert(lot, LotExpiryAlertKind.EXPIRED, now));
        }

        List<InventoryLot> expiring = lotRepository.findEnteringAlertWindow(InventoryLotStatus.ACTIVE, today);
        for (InventoryLot lot : expiring) {
            lot.setExpiringAlertedAt(now);
            factPublisher.recordLotExpiryAlert(alert(lot, LotExpiryAlertKind.EXPIRING, now));
        }

        if (!expired.isEmpty() || !expiring.isEmpty()) {
            log.info("Lot expiry scan: expiredAlerted={} expiringAlerted={}", expired.size(), expiring.size());
        }
        return new LotExpiryScanResult(expired.size(), expiring.size());
    }

    private LotExpiryAlertV1 alert(InventoryLot lot, LotExpiryAlertKind kind, Instant now) {
        return new LotExpiryAlertV1(
                lot.getLotId(), lot.getStockItemId(), lot.getLotNumber(), lot.getExpirationDate(), kind.name(), now);
    }
}
