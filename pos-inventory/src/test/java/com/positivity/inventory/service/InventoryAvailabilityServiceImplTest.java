package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.service.AsOfQueryGuard;
import com.positivity.inventory.internal.service.ForecastQuantityService;
import com.positivity.inventory.internal.service.InventoryAvailabilityServiceImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class InventoryAvailabilityServiceImplTest {

    private static final UUID LOC_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOC_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SLOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private ForecastQuantityService forecastQuantityService;

    @Mock
    private AsOfQueryGuard asOfQueryGuard;

    @Mock
    private com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository
            storageLocationReplicaRepository;

    private InventoryAvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        // Default forecast: projected = onHand (no open supply/demand). Individual tests
        // override where forecast behavior is asserted.
        Mockito.lenient()
                .when(forecastQuantityService.forecast(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong()))
                .thenAnswer(invocation ->
                        new ForecastQuantityService.ForecastQuantities(0L, 0L, invocation.getArgument(3, Long.class)));
        Mockito.lenient()
                .when(storageLocationReplicaRepository.findById(Mockito.any()))
                .thenReturn(java.util.Optional.empty());
        service = new InventoryAvailabilityServiceImpl(
                stockSummaryRepository,
                inventoryLedgerEntryRepository,
                forecastQuantityService,
                asOfQueryGuard,
                new com.positivity.inventory.internal.service.ForecastSiteResolver(storageLocationReplicaRepository),
                java.time.Clock.systemUTC());
    }

    @Test
    void getAvailabilityByProduct_returnsEmptyWhenNoSummaryRowsExist() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString())).thenReturn(List.of());

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

        assertThat(result).isEmpty();
        verify(stockSummaryRepository).findByStockItemId(productId.toString());
    }

    @Test
    void getAvailabilityByProduct_skipsNullLocationRow() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString()))
                .thenReturn(List.of(summary(productId.toString(), null, 5, 0, 0)));

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

        assertThat(result).isEmpty();
    }

    @Test
    void getAvailabilityByProduct_allowsNegativeAtpWhenReservationsExceedOnHand() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString()))
                .thenReturn(List.of(summary(productId.toString(), LOC_1, 2, 0, 5)));

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getLocationId()).isEqualTo(LOC_1);
        assertThat(result.getFirst().getOnHandQuantity()).isEqualTo(2);
        assertThat(result.getFirst().getAvailableToPromiseQuantity()).isEqualTo(-3);
    }

    @Test
    void getAvailabilityByProduct_sortsRowsByLocationId() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString()))
                .thenReturn(List.of(
                        summary(productId.toString(), LOC_2, 60, 0, 0),
                        summary(productId.toString(), LOC_1, 40, 0, 0)));

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getLocationId()).isEqualTo(LOC_1);
        assertThat(result.get(0).getOnHandQuantity()).isEqualTo(40);
        assertThat(result.get(1).getLocationId()).isEqualTo(LOC_2);
        assertThat(result.get(1).getOnHandQuantity()).isEqualTo(60);
    }

    @Test
    void getAvailabilityByProduct_throwsWhenProductIdIsNull() {
        assertThatThrownBy(() -> service.getAvailabilityByProduct(null))
                .isInstanceOf(InvalidInventoryAvailabilityRequestException.class)
                .hasMessage("Product ID is required");
    }

    @Test
    void getAvailabilityByProduct_wrapsRepositoryErrors() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.getAvailabilityByProduct(productId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to retrieve inventory availability at this time");
    }

    @Test
    void getAvailabilityByProduct_subtractsAllocationsAndReservationsFromAtp() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString()))
                .thenReturn(List.of(summary(productId.toString(), LOC_1, 100, 20, 30)));

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOnHandQuantity()).isEqualTo(100);
        assertThat(result.getFirst().getAvailableToPromiseQuantity()).isEqualTo(50);
    }

    @Test
    void queryAvailability_throwsProductNotFoundException_whenNoSummaryRowsExist() {
        when(stockSummaryRepository.findByStockItemId("SKU-123")).thenReturn(List.of());

        assertThatThrownBy(() -> service.queryAvailability("SKU-123", LOC_1, null, null))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product not found: SKU-123");
    }

    @Test
    void queryAvailability_calculatesCorrectly_withLocationAndStorageLocation() {
        String productSku = "SKU-123";
        when(stockSummaryRepository.findByStockItemId(productSku))
                .thenReturn(List.of(summary(productSku, LOC_1, 10, 0, 0), summary(productSku, SLOC_A, 5, 2, 0)));
        when(inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        eq(productSku), eq(SLOC_A), any(Pageable.class)))
                .thenReturn(List.of());

        AvailabilityView result = service.queryAvailability(productSku, LOC_1, SLOC_A, null);

        assertThat(result.getOnHandQuantity()).isEqualTo(5);
        assertThat(result.getAllocatedQuantity()).isEqualTo(2);
        assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(3);
        assertThat(result.getStorageLocationId()).isEqualTo(SLOC_A);
        assertThat(result.getUnitOfMeasure()).isEqualTo("EACH");
    }

    @Test
    void queryAvailability_calculatesCorrectly_withOnlyLocationId() {
        String productSku = "SKU-123";
        when(stockSummaryRepository.findByStockItemId(productSku))
                .thenReturn(List.of(summary(productSku, LOC_1, 10, 2, 7)));
        when(inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        eq(productSku), eq(LOC_1), any(Pageable.class)))
                .thenReturn(List.of("EA"));

        AvailabilityView result = service.queryAvailability(productSku, LOC_1, null, null);

        assertThat(result.getOnHandQuantity()).isEqualTo(10);
        // Per ADR-0001, allocatedQuantity counts hard allocations only, never reservations.
        assertThat(result.getAllocatedQuantity()).isEqualTo(2);
        assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(8);
        assertThat(result.getStorageLocationId()).isNull();
        assertThat(result.getUnitOfMeasure()).isEqualTo("EA");
    }

    @Test
    void queryAvailability_expiredLotsDropFromAtpButStayInOnHand() {
        // odoo-parity E3 (#1047, D-7): ATP = (onHand − allocated) − expired ACTIVE lot on-hand;
        // on-hand itself is unchanged.
        String productSku = "SKU-123";
        when(stockSummaryRepository.findByStockItemId(productSku))
                .thenReturn(List.of(summary(productSku, LOC_1, 10, 2, 0)));
        when(stockSummaryRepository.sumExpiredActiveLotOnHand(
                        eq(productSku), eq(LOC_1), any(java.time.LocalDate.class)))
                .thenReturn(4L);
        when(inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        eq(productSku), eq(LOC_1), any(Pageable.class)))
                .thenReturn(List.of("EA"));

        AvailabilityView result = service.queryAvailability(productSku, LOC_1, null, null);

        assertThat(result.getOnHandQuantity()).isEqualTo(10);
        assertThat(result.getAllocatedQuantity()).isEqualTo(2);
        // 10 onHand − 2 allocated − 4 expired = 4
        assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(4);
    }

    @Test
    void queryAvailability_sumsAllRowsIncludingNullLocation_whenUnscoped() {
        String productSku = "SKU-123";
        when(stockSummaryRepository.findByStockItemId(productSku))
                .thenReturn(List.of(
                        summary(productSku, LOC_1, 10, 1, 0),
                        summary(productSku, LOC_2, 5, 2, 0),
                        summary(productSku, null, -3, 0, 0)));
        when(inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItem(eq(productSku), any(Pageable.class)))
                .thenReturn(List.of());

        AvailabilityView result = service.queryAvailability(productSku, null, null, null);

        assertThat(result.getOnHandQuantity()).isEqualTo(12);
        assertThat(result.getAllocatedQuantity()).isEqualTo(3);
        assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(9);
    }

    @Test
    void queryAvailability_returnsZeroes_whenScopedLocationHasNoRow() {
        String productSku = "SKU-123";
        when(stockSummaryRepository.findByStockItemId(productSku))
                .thenReturn(List.of(summary(productSku, LOC_1, 10, 0, 0)));
        when(inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        eq(productSku), eq(LOC_2), any(Pageable.class)))
                .thenReturn(List.of());

        AvailabilityView result = service.queryAvailability(productSku, LOC_2, null, null);

        assertThat(result.getOnHandQuantity()).isZero();
        assertThat(result.getAllocatedQuantity()).isZero();
        assertThat(result.getAvailableToPromiseQuantity()).isZero();
    }

    @Test
    void getAvailabilityByProduct_populatesForecastFieldsPerSite() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(stockSummaryRepository.findByStockItemId(productId.toString()))
                .thenReturn(List.of(summary(productId.toString(), LOC_1, 100, 20, 0)));
        when(forecastQuantityService.forecast(productId.toString(), LOC_1, null, 100L))
                .thenReturn(new ForecastQuantityService.ForecastQuantities(25L, 10L, 115L));

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getIncomingQty()).isEqualTo(25L);
        assertThat(result.getFirst().getOutgoingQty()).isEqualTo(10L);
        assertThat(result.getFirst().getProjectedAvailable()).isEqualTo(115L);
        // Existing field semantics unchanged.
        assertThat(result.getFirst().getOnHandQuantity()).isEqualTo(100);
        assertThat(result.getFirst().getAvailableToPromiseQuantity()).isEqualTo(80);
    }

    @Test
    void getAvailabilityByProduct_passesHorizonThroughToForecast() {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        java.time.Instant horizon = java.time.Instant.parse("2026-08-01T00:00:00Z");
        when(stockSummaryRepository.findByStockItemId(productId.toString()))
                .thenReturn(List.of(summary(productId.toString(), LOC_1, 40, 0, 0)));
        when(forecastQuantityService.forecast(productId.toString(), LOC_1, horizon, 40L))
                .thenReturn(new ForecastQuantityService.ForecastQuantities(5L, 0L, 45L));

        List<LocationAvailabilityDto> result = service.getAvailabilityByProduct(productId, horizon);

        assertThat(result.getFirst().getIncomingQty()).isEqualTo(5L);
        assertThat(result.getFirst().getProjectedAvailable()).isEqualTo(45L);
        verify(forecastQuantityService).forecast(productId.toString(), LOC_1, horizon, 40L);
    }

    @Test
    void queryAvailability_populatesForecastFields_siteScopedByLocationId() {
        String productSku = "SKU-123";
        when(stockSummaryRepository.findByStockItemId(productSku))
                .thenReturn(List.of(summary(productSku, LOC_1, 10, 2, 0)));
        when(inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        eq(productSku), eq(LOC_1), any(Pageable.class)))
                .thenReturn(List.of());
        when(forecastQuantityService.forecast(productSku, LOC_1, null, 10L))
                .thenReturn(new ForecastQuantityService.ForecastQuantities(7L, 3L, 14L));

        AvailabilityView result = service.queryAvailability(productSku, LOC_1, null, null);

        assertThat(result.getIncomingQty()).isEqualTo(7L);
        assertThat(result.getOutgoingQty()).isEqualTo(3L);
        assertThat(result.getProjectedAvailable()).isEqualTo(14L);
        // Existing field semantics unchanged.
        assertThat(result.getOnHandQuantity()).isEqualTo(10);
        assertThat(result.getAvailableToPromiseQuantity()).isEqualTo(8);
    }

    private InventoryStockSummary summary(
            String stockItemId, UUID locationId, long onHand, long allocated, long reserved) {
        return InventoryStockSummary.builder()
                .stockItemId(stockItemId)
                .locationId(locationId)
                .onHand(onHand)
                .allocated(allocated)
                .reserved(reserved)
                .atp(onHand - allocated)
                .build();
    }

    @Test
    void queryAvailability_resolvesStorageLocationToParentSiteForForecast() {
        // Regression for PR #1058 review: a bin-scoped query must forecast against the
        // bin's PARENT SITE (PO/ASN supply is keyed by ship-to site), not the bin id.
        UUID binId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        String sku = "SKU-BIN-SITE";
        InventoryStockSummary row = summary(sku, binId, 5L, 0L, 0L);
        when(stockSummaryRepository.findByStockItemId(sku)).thenReturn(List.of(row));
        com.positivity.inventory.internal.entity.ExtStorageLocationReplica replica =
                com.positivity.inventory.internal.entity.ExtStorageLocationReplica.builder()
                        .storageLocationId(binId)
                        .siteId(siteId)
                        .build();
        when(storageLocationReplicaRepository.findById(binId)).thenReturn(java.util.Optional.of(replica));

        service.queryAvailability(sku, null, binId, null);

        verify(forecastQuantityService)
                .forecast(Mockito.eq(sku), Mockito.eq(siteId), Mockito.isNull(), Mockito.anyLong());
    }
}
