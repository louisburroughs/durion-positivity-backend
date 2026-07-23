package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.LotExpiryAlertV1;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.LotExpiryAlertKind;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.service.LotExpiryScanService.LotExpiryScanResult;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Daily lot-expiry scan tests for odoo-parity E3 (issue #1047): EXPIRED/EXPIRING alerts are
 * emitted once per transition, the emit-once stamp is written, and a re-run does not re-emit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Lot expiry scan")
class LotExpiryScanServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);

    @Mock
    private InventoryLotRepository lotRepository;

    @Mock
    private InventoryFactPublisher factPublisher;

    private LotExpiryScanService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        service = new LotExpiryScanService(lotRepository, factPublisher, clock);
    }

    private InventoryLot lot(String number, LocalDate expiry) {
        return InventoryLot.builder()
                .lotId(UUID.randomUUID())
                .stockItemId("01960003-0000-7000-8000-000000000001")
                .lotNumber(number)
                .expirationDate(expiry)
                .status(InventoryLotStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Newly expired lot: EXPIRED fact emitted once and expiredAlertedAt stamped")
    void emitsExpiredOnceAndStamps() {
        InventoryLot expired = lot("LOT-EXPIRED", TODAY.minusDays(1));
        when(lotRepository.findNewlyExpired(eq(InventoryLotStatus.ACTIVE), eq(TODAY)))
                .thenReturn(List.of(expired));
        when(lotRepository.findEnteringAlertWindow(eq(InventoryLotStatus.ACTIVE), eq(TODAY)))
                .thenReturn(List.of());

        LotExpiryScanResult result = service.runExpiryScan();

        assertThat(result.expiredAlerted()).isEqualTo(1);
        assertThat(result.expiringAlerted()).isZero();
        assertThat(expired.getExpiredAlertedAt()).isNotNull();

        ArgumentCaptor<LotExpiryAlertV1> captor = ArgumentCaptor.forClass(LotExpiryAlertV1.class);
        verify(factPublisher).recordLotExpiryAlert(captor.capture());
        assertThat(captor.getValue().alertKind()).isEqualTo(LotExpiryAlertKind.EXPIRED.name());
        assertThat(captor.getValue().lotId()).isEqualTo(expired.getLotId());
    }

    @Test
    @DisplayName("Lot entering alert window: EXPIRING fact emitted and expiringAlertedAt stamped")
    void emitsExpiringAndStamps() {
        InventoryLot expiring = lot("LOT-SOON", TODAY.plusDays(5));
        expiring.setAlertDate(TODAY.minusDays(1));
        when(lotRepository.findNewlyExpired(any(), any())).thenReturn(List.of());
        when(lotRepository.findEnteringAlertWindow(eq(InventoryLotStatus.ACTIVE), eq(TODAY)))
                .thenReturn(List.of(expiring));

        LotExpiryScanResult result = service.runExpiryScan();

        assertThat(result.expiringAlerted()).isEqualTo(1);
        assertThat(expiring.getExpiringAlertedAt()).isNotNull();

        ArgumentCaptor<LotExpiryAlertV1> captor = ArgumentCaptor.forClass(LotExpiryAlertV1.class);
        verify(factPublisher).recordLotExpiryAlert(captor.capture());
        assertThat(captor.getValue().alertKind()).isEqualTo(LotExpiryAlertKind.EXPIRING.name());
    }

    @Test
    @DisplayName("Re-run with nothing newly transitioned emits no facts (idempotent)")
    void idempotentReRun() {
        when(lotRepository.findNewlyExpired(any(), any())).thenReturn(List.of());
        when(lotRepository.findEnteringAlertWindow(any(), any())).thenReturn(List.of());

        LotExpiryScanResult result = service.runExpiryScan();

        assertThat(result.expiredAlerted()).isZero();
        assertThat(result.expiringAlerted()).isZero();
        verify(factPublisher, never()).recordLotExpiryAlert(any());
    }
}
