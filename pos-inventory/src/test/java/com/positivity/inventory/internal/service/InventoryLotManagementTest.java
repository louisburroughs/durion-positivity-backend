package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.lot.LotExpirationUpdateRequest;
import com.positivity.inventory.internal.dto.lot.LotResponse;
import com.positivity.inventory.internal.dto.lot.LotStatusUpdateRequest;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.tracking.service.InventoryLotServiceImpl;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Lot-management tests for odoo-parity E3 (issue #1047): status flip validation and expiration set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Lot management (status + expiration)")
class InventoryLotManagementTest {

    @Mock
    private InventoryLotRepository lotRepository;

    @Mock
    private InventoryStockSummaryRepository summaryRepository;

    private InventoryLotServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InventoryLotServiceImpl(lotRepository, summaryRepository);
    }

    private InventoryLot activeLot(UUID id) {
        return InventoryLot.builder()
                .lotId(id)
                .stockItemId("01960003-0000-7000-8000-000000000001")
                .lotNumber("LOT-1")
                .receivedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expirationDate(LocalDate.of(2026, 12, 1))
                .status(InventoryLotStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Quarantine flips status to QUARANTINED")
    void quarantine() {
        UUID id = UUID.randomUUID();
        InventoryLot lot = activeLot(id);
        when(lotRepository.findById(id)).thenReturn(Optional.of(lot));
        when(lotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LotResponse response =
                service.updateStatus(id, new LotStatusUpdateRequest(InventoryLotStatus.QUARANTINED, "recall"));

        assertThat(response.getStatus()).isEqualTo(InventoryLotStatus.QUARANTINED);
        assertThat(lot.getStatus()).isEqualTo(InventoryLotStatus.QUARANTINED);
    }

    @Test
    @DisplayName("Setting CONSUMED via the API is rejected")
    void consumedRejected() {
        assertThatThrownBy(() -> service.updateStatus(
                        UUID.randomUUID(), new LotStatusUpdateRequest(InventoryLotStatus.CONSUMED, "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Blank reason is rejected")
    void blankReasonRejected() {
        assertThatThrownBy(() -> service.updateStatus(
                        UUID.randomUUID(), new LotStatusUpdateRequest(InventoryLotStatus.RECALLED, "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Unknown lot yields 404")
    void unknownLot() {
        UUID id = UUID.randomUUID();
        when(lotRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateStatus(id, new LotStatusUpdateRequest(InventoryLotStatus.RECALLED, "r")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Setting expiration also resets emit-once alert bookkeeping")
    void updateExpirationResetsStamps() {
        UUID id = UUID.randomUUID();
        InventoryLot lot = activeLot(id);
        lot.setExpiredAlertedAt(Instant.parse("2026-06-01T00:00:00Z"));
        lot.setExpiringAlertedAt(Instant.parse("2026-05-01T00:00:00Z"));
        when(lotRepository.findById(id)).thenReturn(Optional.of(lot));
        when(lotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LotResponse response = service.updateExpiration(
                id, new LotExpirationUpdateRequest(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 1, 1)));

        assertThat(response.getExpirationDate()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(lot.getAlertDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(lot.getExpiredAlertedAt()).isNull();
        assertThat(lot.getExpiringAlertedAt()).isNull();
    }
}
