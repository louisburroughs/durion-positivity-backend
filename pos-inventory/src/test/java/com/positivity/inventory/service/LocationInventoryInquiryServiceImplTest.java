package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.service.AsOfQueryGuard;
import com.positivity.inventory.internal.service.LocationInventoryInquiryServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LocationInventoryInquiryServiceImpl} backed by the
 * stock summary read model (issue #1024, A1).
 */
@ExtendWith(MockitoExtension.class)
class LocationInventoryInquiryServiceImplTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000000047");

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private AsOfQueryGuard asOfQueryGuard;

    private LocationInventoryInquiryServiceImpl newService() {
        return new LocationInventoryInquiryServiceImpl(
                stockSummaryRepository, inventoryLedgerEntryRepository, asOfQueryGuard);
    }

    private InventoryStockSummary summary(String sku, long onHand, long allocated) {
        return InventoryStockSummary.builder()
                .stockItemId(sku)
                .locationId(LOCATION_ID)
                .onHand(onHand)
                .allocated(allocated)
                .atp(onHand - allocated)
                .build();
    }

    @Test
    void listLocationInventoryItems_mapsPositiveOnHandRowsToItems() {
        var service = newService();
        when(stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(LOCATION_ID, 0L))
                .thenReturn(List.of(summary("MICH-XC2-0001", 24L, 0L), summary("MICH-DEF-0002", 8L, 0L)));

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getStockItemId)
                .containsExactly("MICH-XC2-0001", "MICH-DEF-0002");
        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getOnHandQuantity)
                .containsExactly(24L, 8L);
    }

    @Test
    void listLocationInventoryItems_returnsEmptyListForLocationWithoutStock() {
        var service = newService();
        when(stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(LOCATION_ID, 0L))
                .thenReturn(List.of());

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getItems()).isEmpty();
    }

    @Test
    void getLocationInventory_withoutSku_sumsAcrossStockItems() {
        var service = newService();
        when(stockSummaryRepository.sumOnHandAtLocation(LOCATION_ID)).thenReturn(32L);
        when(stockSummaryRepository.sumAllocatedAtLocation(LOCATION_ID)).thenReturn(5L);

        LocationInventoryInquiryResponse response = service.getLocationInventory(LOCATION_ID, null);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getOnHandQuantity()).isEqualTo(32);
        assertThat(response.getAvailableToPromiseQuantity()).isEqualTo(27);
    }

    @Test
    void getLocationInventory_withSku_usesSingleSummaryRow() {
        var service = newService();
        when(stockSummaryRepository.findByStockItemIdAndLocationId("MICH-XC2-0001", LOCATION_ID))
                .thenReturn(Optional.of(summary("MICH-XC2-0001", 24L, 4L)));

        LocationInventoryInquiryResponse response = service.getLocationInventory(LOCATION_ID, "MICH-XC2-0001");

        assertThat(response.getOnHandQuantity()).isEqualTo(24);
        assertThat(response.getAvailableToPromiseQuantity()).isEqualTo(20);
    }

    @Test
    void getLocationInventory_withUnknownSku_returnsZeroes() {
        var service = newService();
        when(stockSummaryRepository.findByStockItemIdAndLocationId("NOPE", LOCATION_ID))
                .thenReturn(Optional.empty());

        LocationInventoryInquiryResponse response = service.getLocationInventory(LOCATION_ID, "NOPE");

        assertThat(response.getOnHandQuantity()).isZero();
        assertThat(response.getAvailableToPromiseQuantity()).isZero();
    }
}
