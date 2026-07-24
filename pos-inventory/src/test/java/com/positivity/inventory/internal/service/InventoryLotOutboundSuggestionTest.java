package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.ProductTrackingLevel;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Lot-suggestion tests for odoo-parity E3 (issue #1047): FEFO ordering when expiry is present,
 * FIFO fallback when absent, and the on-read guard that never proposes an expired,
 * QUARANTINED, or RECALLED lot even before the daily expiry scan has run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Lot suggestion (FEFO + expiry/quarantine guard)")
class InventoryLotOutboundSuggestionTest {

    private static final String SKU = "01960003-0000-7000-8000-000000000001";
    private static final UUID LOCATION = UUID.fromString("01960003-0000-7000-8000-0000000000aa");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);

    @Mock
    private InventoryLotRepository lotRepository;

    @Mock
    private InventoryStockSummaryRepository summaryRepository;

    @Mock
    private ProductTrackingLevelService trackingLevelService;

    private InventoryLotOutboundService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        service = new InventoryLotOutboundService(lotRepository, summaryRepository, trackingLevelService, clock);
        when(trackingLevelService.trackingLevelFor(anyString())).thenReturn(ProductTrackingLevel.LOT);
    }

    private InventoryStockSummary perLotRow(UUID lotId) {
        InventoryStockSummary row = new InventoryStockSummary();
        row.setStockItemId(SKU);
        row.setLocationId(LOCATION);
        row.setLotId(lotId);
        row.setOnHand(5);
        return row;
    }

    private InventoryLot lot(UUID id, String number, Instant receivedAt, LocalDate expiry, InventoryLotStatus status) {
        return InventoryLot.builder()
                .lotId(id)
                .stockItemId(SKU)
                .lotNumber(number)
                .receivedAt(receivedAt)
                .expirationDate(expiry)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("FEFO: earliest expiry wins even when it was received later")
    void fefo_ordersByEarliestExpiry() {
        UUID earlyExpiryLot = UUID.randomUUID();
        UUID lateExpiryLot = UUID.randomUUID();
        when(summaryRepository.findPositivePerLotRows(SKU, LOCATION))
                .thenReturn(List.of(perLotRow(earlyExpiryLot), perLotRow(lateExpiryLot)));
        when(lotRepository.findAllById(any()))
                .thenReturn(List.of(
                        // received EARLIER but expires LATER
                        lot(
                                lateExpiryLot,
                                "LOT-LATE",
                                Instant.parse("2026-01-01T00:00:00Z"),
                                LocalDate.of(2026, 12, 1),
                                InventoryLotStatus.ACTIVE),
                        // received LATER but expires SOONER → FEFO picks this
                        lot(
                                earlyExpiryLot,
                                "LOT-EARLY",
                                Instant.parse("2026-05-01T00:00:00Z"),
                                LocalDate.of(2026, 8, 1),
                                InventoryLotStatus.ACTIVE)));

        assertThat(service.suggestLotNumber(SKU, LOCATION)).isEqualTo("LOT-EARLY");
    }

    @Test
    @DisplayName("FIFO fallback: lots without expiry order by earliest receivedAt")
    void fifo_fallbackWhenNoExpiry() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        when(summaryRepository.findPositivePerLotRows(SKU, LOCATION))
                .thenReturn(List.of(perLotRow(older), perLotRow(newer)));
        when(lotRepository.findAllById(any()))
                .thenReturn(List.of(
                        lot(newer, "LOT-NEW", Instant.parse("2026-05-01T00:00:00Z"), null, InventoryLotStatus.ACTIVE),
                        lot(older, "LOT-OLD", Instant.parse("2026-01-01T00:00:00Z"), null, InventoryLotStatus.ACTIVE)));

        assertThat(service.suggestLotNumber(SKU, LOCATION)).isEqualTo("LOT-OLD");
    }

    @Test
    @DisplayName("Expired lots are guarded out before the job runs; the next good lot is suggested")
    void expiredLotGuardedOut() {
        UUID expired = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(summaryRepository.findPositivePerLotRows(SKU, LOCATION))
                .thenReturn(List.of(perLotRow(expired), perLotRow(good)));
        when(lotRepository.findAllById(any()))
                .thenReturn(List.of(
                        // expired yesterday — earliest expiry, but must NOT be suggested
                        lot(
                                expired,
                                "LOT-EXPIRED",
                                Instant.parse("2026-01-01T00:00:00Z"),
                                TODAY.minusDays(1),
                                InventoryLotStatus.ACTIVE),
                        lot(
                                good,
                                "LOT-GOOD",
                                Instant.parse("2026-02-01T00:00:00Z"),
                                TODAY.plusDays(30),
                                InventoryLotStatus.ACTIVE)));

        assertThat(service.suggestLotNumber(SKU, LOCATION)).isEqualTo("LOT-GOOD");
    }

    @Test
    @DisplayName("QUARANTINED and RECALLED lots are never suggested")
    void blockedLotsExcluded() {
        UUID quarantined = UUID.randomUUID();
        UUID recalled = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(summaryRepository.findPositivePerLotRows(SKU, LOCATION))
                .thenReturn(List.of(perLotRow(quarantined), perLotRow(recalled), perLotRow(good)));
        when(lotRepository.findAllById(any()))
                .thenReturn(List.of(
                        lot(
                                quarantined,
                                "LOT-Q",
                                Instant.parse("2026-01-01T00:00:00Z"),
                                TODAY.plusDays(5),
                                InventoryLotStatus.QUARANTINED),
                        lot(
                                recalled,
                                "LOT-R",
                                Instant.parse("2026-01-02T00:00:00Z"),
                                TODAY.plusDays(6),
                                InventoryLotStatus.RECALLED),
                        lot(
                                good,
                                "LOT-GOOD",
                                Instant.parse("2026-03-01T00:00:00Z"),
                                TODAY.plusDays(20),
                                InventoryLotStatus.ACTIVE)));

        assertThat(service.suggestLotNumber(SKU, LOCATION)).isEqualTo("LOT-GOOD");
    }

    @Test
    @DisplayName("All candidates expired/blocked → no suggestion")
    void noEligibleLot_returnsNull() {
        UUID expired = UUID.randomUUID();
        when(summaryRepository.findPositivePerLotRows(SKU, LOCATION)).thenReturn(List.of(perLotRow(expired)));
        when(lotRepository.findAllById(any()))
                .thenReturn(List.of(lot(
                        expired,
                        "LOT-EXPIRED",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        TODAY.minusDays(1),
                        InventoryLotStatus.ACTIVE)));

        assertThat(service.suggestLotNumber(SKU, LOCATION)).isNull();
    }
}
