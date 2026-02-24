package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                                productId.toString(),
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
                                productId.toString(),
                                InventoryLedgerEventType.GOODS_RECEIPT,
                                2,
                                "LOC-1");
                InventoryLedgerEntry reservationEntry = ledgerEntry(
                                productId.toString(),
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

        @Test
        void getAvailabilityByProduct_throwsWhenProductIdIsNull() {
                assertThatThrownBy(() -> service.getAvailabilityByProduct(null))
                                .isInstanceOf(InvalidInventoryAvailabilityRequestException.class)
                                .hasMessage("Product ID is required");
        }

        @Test
        void getAvailabilityByProduct_wrapsRepositoryErrors() {
                UUID productId = UUID.randomUUID();
                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenThrow(new RuntimeException("db down"));

                assertThatThrownBy(() -> service.getAvailabilityByProduct(productId))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("Unable to retrieve inventory availability at this time");
        }

        @Test
        void getAvailabilityByProduct_skipsNullQuantityEntries() {
                UUID productId = UUID.randomUUID();
                InventoryLedgerEntry invalidEntry = ledgerEntry(productId.toString(),
                                InventoryLedgerEventType.GOODS_RECEIPT, 1,
                                "LOC-1");
                invalidEntry.setChangeInQuantity(null);

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of(invalidEntry));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);
                assertThat(result).isEmpty();
        }

        @Test
        void getAvailabilityByProduct_skipsNullLedgerEntry() {
                UUID productId = UUID.randomUUID();
                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(java.util.Arrays.asList(
                                                ledgerEntry(productId.toString(),
                                                                InventoryLedgerEventType.GOODS_RECEIPT, 1, "LOC-1"),
                                                null));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);
                assertThat(result).hasSize(1);
                assertThat(result.get(0).getOnHandQuantity()).isEqualTo(1);
        }

        @Test
        void getAvailabilityByProduct_handlesNullEventType() {
                UUID productId = UUID.randomUUID();
                InventoryLedgerEntry entry = ledgerEntry(productId.toString(), null, 5, "LOC-1");
                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of(entry));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getOnHandQuantity()).isZero();
                assertThat(result.get(0).getAvailableToPromiseQuantity()).isZero();
        }

        @Test
        void queryAvailability_handlesNullChangeInQuantity() {
                String productSku = "SKU-123";
                String locationId = "LOC-1";
                InventoryLedgerEntry entry = ledgerEntry(productSku, InventoryLedgerEventType.GOODS_RECEIPT, 10,
                                locationId);
                entry.setChangeInQuantity(null);

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productSku))
                                .thenReturn(List.of(entry));
                when(inventoryLedgerEntryRepository.findByStockItemIdAndLocationIdOrderByTimestampAsc(productSku,
                                locationId))
                                .thenReturn(List.of(entry));

                AvailabilityView result = service.queryAvailability(productSku, locationId, null);

                assertThat(result.getOnHandQuantity()).isZero();
                assertThat(result.getAllocatedQuantity()).isZero();
                assertThat(result.getAvailableToPromiseQuantity()).isZero();
        }

        @Test
        void getAvailabilityByProduct_handlesReservationReleased() {
                UUID productId = UUID.randomUUID();
                InventoryLedgerEntry reservationEntry = ledgerEntry(
                                productId.toString(),
                                InventoryLedgerEventType.RESERVATION_RELEASED,
                                5,
                                "LOC-1");

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of(reservationEntry));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

                assertThat(result).hasSize(1);
                assertThat(result.getFirst().getAvailableToPromiseQuantity()).isEqualTo(5);
        }

        @Test
        void getAvailabilityByProduct_handlesNullQuantityInSafeQuantity() {
                UUID productId = UUID.randomUUID();
                InventoryLedgerEntry entry = ledgerEntry(productId.toString(),
                                InventoryLedgerEventType.RESERVATION_CREATED, 1,
                                "LOC-1");
                entry.setChangeInQuantity(null);

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString()))
                                .thenReturn(List.of(entry));

                List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);
                assertThat(result).isEmpty();
        }

        @Test
        void queryAvailability_throwsProductNotFoundException_whenNoEntriesExist() {
                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc("SKU-123"))
                                .thenReturn(List.of());

                assertThatThrownBy(() -> service.queryAvailability("SKU-123", "LOC-1", null))
                                .isInstanceOf(ProductNotFoundException.class)
                                .hasMessage("Product not found: SKU-123");
        }

        @Test
        void queryAvailability_calculatesCorrectly_withLocationAndStorageLocation() {
                String productSku = "SKU-123";
                String locationId = "LOC-1";
                String storageLocationId = "SLOC-A";

                List<InventoryLedgerEntry> productEntries = List.of(
                                ledgerEntry(productSku, InventoryLedgerEventType.GOODS_RECEIPT, 10, locationId));
                List<InventoryLedgerEntry> locationEntries = List.of(
                                ledgerEntry(productSku, InventoryLedgerEventType.GOODS_RECEIPT, 5, storageLocationId),
                                ledgerEntry(productSku, InventoryLedgerEventType.ALLOCATION_CREATED, 2,
                                                storageLocationId));

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productSku))
                                .thenReturn(productEntries);
                when(inventoryLedgerEntryRepository.findByStockItemIdAndLocationIdOrderByTimestampAsc(productSku,
                                storageLocationId))
                                .thenReturn(locationEntries);

                AvailabilityView result = service.queryAvailability(productSku, locationId, storageLocationId);

                assertThat(result.getOnHandQuantity()).isEqualTo(5);
                assertThat(result.getAllocatedQuantity()).isEqualTo(2);
                assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(3);
                assertThat(result.getStorageLocationId()).isEqualTo(storageLocationId);
        }

        @Test
        void queryAvailability_calculatesCorrectly_withOnlyLocationId() {
                String productSku = "SKU-123";
                String locationId = "LOC-1";

                List<InventoryLedgerEntry> productEntries = List.of(
                                ledgerEntry(productSku, InventoryLedgerEventType.GOODS_RECEIPT, 10, locationId));
                List<InventoryLedgerEntry> locationEntries = List.of(
                                ledgerEntry(productSku, InventoryLedgerEventType.GOODS_RECEIPT, 10, locationId),
                                ledgerEntry(productSku, InventoryLedgerEventType.ALLOCATION_CREATED, 3, locationId),
                                ledgerEntry(productSku, InventoryLedgerEventType.ALLOCATION_RELEASED, 1, locationId));

                when(inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productSku))
                                .thenReturn(productEntries);
                when(inventoryLedgerEntryRepository.findByStockItemIdAndLocationIdOrderByTimestampAsc(productSku,
                                locationId))
                                .thenReturn(locationEntries);

                AvailabilityView result = service.queryAvailability(productSku, locationId, " "); // blank storage id

                assertThat(result.getOnHandQuantity()).isEqualTo(10);
                assertThat(result.getAllocatedQuantity()).isEqualTo(2); // 3 created - 1 released
                assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(8);
                assertThat(result.getStorageLocationId()).isNull();
        }

        private InventoryLedgerEntry ledgerEntry(String stockItemId, InventoryLedgerEventType eventType,
                        int changeInQuantity,
                        String locationId) {
                return InventoryLedgerEntry.builder()
                                .stockItemId(stockItemId)
                                .eventType(eventType)
                                .changeInQuantity(changeInQuantity)
                                .quantityAfter(0)
                                .transactionUserId("test-user")
                                .timestamp(Instant.now())
                                .locationId(locationId)
                                .build();
        }
}
