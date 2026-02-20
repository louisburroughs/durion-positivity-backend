package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.model.InventoryLedgerEntry;
import com.positivity.inventory.internal.model.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.InventoryAvailabilityServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryAvailabilityServiceImplTest {

        @Mock
        private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

        private InventoryAvailabilityServiceImpl service;

        @BeforeEach
        void setUp() {
                service = new InventoryAvailabilityServiceImpl(inventoryLedgerEntryRepository);
        }

        @Test
        void getAvailabilityByProduct_returnsEmptyWhenNoEntriesExist() {
                UUID productId = UUID.randomUUID();
                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of());

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

                assertThat(result).isEmpty();
                verify(inventoryLedgerEntryRepository).findByStockItemIdOrderByTimestampAsc(productId.toString());
        }

        @Test
        void getAvailabilityByProduct_usesDefaultLocationWhenLocationIsBlank() {
                UUID productId = UUID.randomUUID();
                InventoryLedgerEntry onHandEntry = ledgerEntry(
                                productId,
                                InventoryLedgerEventType.GOODS_RECEIPT,
                                5,
                                " ");

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of(onHandEntry));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

                assertThat(result).hasSize(1);
                assertThat(result.getFirst().getLocationId()).isEqualTo("DEFAULT");
                assertThat(result.getFirst().getOnHandQuantity()).isEqualTo(5);
                assertThat(result.getFirst().getAvailableToPromiseQuantity()).isEqualTo(5);
        }

        @Test
        void getAvailabilityByProduct_allowsNegativeAtpWhenReservationsExceedOnHand() {
                UUID productId = UUID.randomUUID();
                InventoryLedgerEntry onHandEntry = ledgerEntry(
                                productId,
                                InventoryLedgerEventType.GOODS_RECEIPT,
                                2,
                                "LOC-1");
                InventoryLedgerEntry reservationEntry = ledgerEntry(
                                productId,
                                InventoryLedgerEventType.RESERVATION_CREATED,
                                5,
                                "LOC-1");

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of(onHandEntry, reservationEntry));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

                assertThat(result).hasSize(1);
                assertThat(result.getFirst().getLocationId()).isEqualTo("LOC-1");
                assertThat(result.getFirst().getOnHandQuantity()).isEqualTo(2);
                assertThat(result.getFirst().getAvailableToPromiseQuantity()).isEqualTo(-3);
        }

        private InventoryLedgerEntry ledgerEntry(UUID productId, InventoryLedgerEventType eventType,
                        int changeInQuantity,
                        String locationId) {
                return InventoryLedgerEntry.builder()
                                .stockItemId(productId.toString())
                                .eventType(eventType)
                                .changeInQuantity(changeInQuantity)
                                .quantityAfter(0)
                                .transactionUserId("test-user")
                                .timestamp(Instant.now())
                                .locationId(locationId)
                                .build();
        }
}
