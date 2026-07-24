package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates {@link InventoryLot} master rows in a {@code REQUIRES_NEW} transaction (odoo-parity
 * E1, issue #1038), mirroring the A1 {@link StockSummaryRowInitializer} pattern: a lost
 * unique-constraint race aborts only this inner transaction, so
 * {@link InventoryLotCaptureService} can catch the violation and re-read the winner's row
 * without its own posting transaction being poisoned. Separate bean because Spring's
 * transactional proxying does not apply to self-invocation.
 */
@Component
@Slf4j
public class InventoryLotCreator {

    private final InventoryLotRepository lotRepository;
    private final Clock clock;

    public InventoryLotCreator(InventoryLotRepository lotRepository, Clock clock) {
        this.lotRepository = lotRepository;
        this.clock = clock;
    }

    /**
     * Inserts the lot row; throws {@code DataIntegrityViolationException} on a lost race.
     * {@code expirationDate} (odoo-parity E3, issue #1047) is stamped on first creation when the
     * receiving document carries one; null otherwise (set later via the lot-management endpoint).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @NonNull
    public InventoryLot create(
            @NonNull String stockItemId,
            @NonNull String lotNumber,
            @Nullable UUID vendorId,
            @Nullable LocalDate expirationDate) {
        InventoryLot created = lotRepository.saveAndFlush(InventoryLot.builder()
                .stockItemId(stockItemId)
                .lotNumber(lotNumber)
                .receivedAt(Instant.now(clock))
                .vendorId(vendorId)
                .expirationDate(expirationDate)
                .status(InventoryLotStatus.ACTIVE)
                .build());
        log.info(
                "Created inventory lot {} for stockItemId={} lotNumber={}", created.getLotId(), stockItemId, lotNumber);
        return created;
    }
}
