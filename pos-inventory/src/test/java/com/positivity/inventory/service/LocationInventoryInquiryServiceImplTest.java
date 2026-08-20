package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.service.AsOfQueryGuard;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import com.positivity.inventory.internal.service.LocationInventoryInquiryServiceImpl;
import java.math.BigDecimal;
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

    @Mock
    private BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    private LocationInventoryInquiryServiceImpl newService() {
        return new LocationInventoryInquiryServiceImpl(
                stockSummaryRepository, inventoryLedgerEntryRepository, asOfQueryGuard, baseUnitOfMeasureResolver);
    }

    private InventoryStockSummary summary(String sku, long onHand, long allocated) {
        return InventoryStockSummary.builder()
                .stockItemId(sku)
                .locationId(LOCATION_ID)
                .onHand(BigDecimal.valueOf(onHand))
                .allocated(BigDecimal.valueOf(allocated))
                .atp(BigDecimal.valueOf(onHand - allocated))
                .build();
    }

    @Test
    void listLocationInventoryItems_mapsPositiveOnHandRowsToItems() {
        var service = newService();
        when(stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(LOCATION_ID, new BigDecimal("0")))
                .thenReturn(List.of(summary("MICH-XC2-0001", 24L, 0L), summary("MICH-DEF-0002", 8L, 0L)));

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getStockItemId)
                .containsExactly("MICH-XC2-0001", "MICH-DEF-0002");
        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getOnHandQuantity)
                .containsExactly(new BigDecimal("24"), new BigDecimal("8"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "#1418 review: resolves units of measure with a single batched call, not one per row")
    void listLocationInventoryItems_resolvesUnitsOfMeasureInOneBatchedCall() {
        var service = newService();
        UUID productA = UUID.fromString("01960004-0002-7000-8000-0000000000a1");
        UUID productB = UUID.fromString("01960004-0002-7000-8000-0000000000a2");
        when(stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(LOCATION_ID, new BigDecimal("0")))
                .thenReturn(List.of(summary(productA.toString(), 24L, 0L), summary(productB.toString(), 8L, 0L)));
        when(baseUnitOfMeasureResolver.resolveAll(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of(productA, "EA", productB, "QT"));

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getUnitOfMeasure)
                .containsExactly("EA", "QT");
        org.mockito.Mockito.verify(baseUnitOfMeasureResolver).resolveAll(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(baseUnitOfMeasureResolver, org.mockito.Mockito.never())
                .resolve(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(baseUnitOfMeasureResolver, org.mockito.Mockito.never())
                .resolve(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void listLocationInventoryItems_returnsEmptyListForLocationWithoutStock() {
        var service = newService();
        when(stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(LOCATION_ID, new BigDecimal("0")))
                .thenReturn(List.of());

        LocationInventoryItemsResponse response = service.listLocationInventoryItems(LOCATION_ID);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getItems()).isEmpty();
    }

    @Test
    void getLocationInventory_withoutSku_sumsAcrossStockItems() {
        var service = newService();
        when(stockSummaryRepository.sumOnHandAtLocation(LOCATION_ID)).thenReturn(new BigDecimal("32"));
        when(stockSummaryRepository.sumAllocatedAtLocation(LOCATION_ID)).thenReturn(new BigDecimal("5"));

        LocationInventoryInquiryResponse response = service.getLocationInventory(LOCATION_ID, null);

        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getOnHandQuantity()).isEqualByComparingTo("32");
        assertThat(response.getAvailableToPromiseQuantity()).isEqualByComparingTo("27");
    }

    @Test
    void getLocationInventory_withSku_usesSingleSummaryRow() {
        var service = newService();
        when(stockSummaryRepository.findByStockItemIdAndLocationId("MICH-XC2-0001", LOCATION_ID))
                .thenReturn(Optional.of(summary("MICH-XC2-0001", 24L, 4L)));

        LocationInventoryInquiryResponse response = service.getLocationInventory(LOCATION_ID, "MICH-XC2-0001");

        assertThat(response.getOnHandQuantity()).isEqualByComparingTo("24");
        assertThat(response.getAvailableToPromiseQuantity()).isEqualByComparingTo("20");
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

    private record TestLocationOnHand(String stockItemId, BigDecimal onHandQuantity)
            implements InventoryLedgerEntryRepository.LocationOnHand {
        @Override
        public String getStockItemId() {
            return stockItemId;
        }

        @Override
        public BigDecimal getOnHandQuantity() {
            return onHandQuantity;
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "#1418 review: listLocationInventoryItemsAsOf resolves units of measure with a single batched call")
    void listLocationInventoryItemsAsOf_resolvesUnitsOfMeasureInOneBatchedCall() {
        var service = newService();
        UUID productA = UUID.fromString("01960004-0002-7000-8000-0000000000b1");
        UUID productB = UUID.fromString("01960004-0002-7000-8000-0000000000b2");
        java.time.Instant asOf = java.time.Instant.parse("2026-08-01T00:00:00Z");
        when(inventoryLedgerEntryRepository.findPositiveOnHandByLocationAsOf(
                        org.mockito.ArgumentMatchers.eq(LOCATION_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(asOf)))
                .thenReturn(List.of(
                        new TestLocationOnHand(productA.toString(), new BigDecimal("24")),
                        new TestLocationOnHand(productB.toString(), new BigDecimal("8"))));
        when(baseUnitOfMeasureResolver.resolveAll(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of(productA, "EA", productB, "QT"));

        LocationInventoryItemsResponse response = service.listLocationInventoryItemsAsOf(LOCATION_ID, asOf);

        assertThat(response.getItems())
                .extracting(LocationInventoryItemsResponse.Item::getUnitOfMeasure)
                .containsExactly("EA", "QT");
        org.mockito.Mockito.verify(baseUnitOfMeasureResolver).resolveAll(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(baseUnitOfMeasureResolver, org.mockito.Mockito.never())
                .resolve(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(baseUnitOfMeasureResolver, org.mockito.Mockito.never())
                .resolve(org.mockito.ArgumentMatchers.any(UUID.class));
    }
}
