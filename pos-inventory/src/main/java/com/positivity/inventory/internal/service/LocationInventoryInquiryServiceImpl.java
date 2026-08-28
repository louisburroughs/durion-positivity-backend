package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Location-level on-hand inquiry served from the {@code inventory_stock_summary}
 * read model (issue #1024, A1) instead of aggregating the ledger per request.
 *
 * <p>Point-in-time (as-of) variants (odoo-parity A3, issue #1029) bypass the summary and
 * aggregate the ledger directly with a timestamp bound; they return on-hand only.
 */
@Service
public class LocationInventoryInquiryServiceImpl implements LocationInventoryInquiryService {

    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final AsOfQueryGuard asOfQueryGuard;
    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    public LocationInventoryInquiryServiceImpl(
            InventoryStockSummaryRepository stockSummaryRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            AsOfQueryGuard asOfQueryGuard,
            BaseUnitOfMeasureResolver baseUnitOfMeasureResolver) {
        this.stockSummaryRepository = stockSummaryRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.asOfQueryGuard = asOfQueryGuard;
        this.baseUnitOfMeasureResolver = baseUnitOfMeasureResolver;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryInquiryResponse getLocationInventory(@NonNull UUID locationId, @Nullable String sku) {
        BigDecimal onHandQuantity;
        BigDecimal outstandingAllocations;
        if (sku == null || sku.isBlank()) {
            onHandQuantity = Quantities.nz(stockSummaryRepository.sumOnHandAtLocation(locationId));
            outstandingAllocations = Quantities.nz(stockSummaryRepository.sumAllocatedAtLocation(locationId));
        } else {
            InventoryStockSummary row = stockSummaryRepository
                    .findByStockItemIdAndLocationId(sku, locationId)
                    .orElse(null);
            onHandQuantity = row == null ? BigDecimal.ZERO : Quantities.nz(row.getOnHand());
            outstandingAllocations = row == null ? BigDecimal.ZERO : Quantities.nz(row.getAllocated());
        }

        return LocationInventoryInquiryResponse.builder()
                .locationId(locationId)
                .onHandQuantity(onHandQuantity)
                .availableToPromiseQuantity(onHandQuantity.subtract(outstandingAllocations))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryItemsResponse listLocationInventoryItems(@NonNull UUID locationId) {
        List<InventoryStockSummary> rows =
                stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(locationId, BigDecimal.ZERO);
        // One batched IN query for the whole page instead of one BaseUnitOfMeasureResolver round
        // trip per row.
        Map<UUID, String> uomByProductId = baseUnitOfMeasureResolver.resolveAll(rows.stream()
                .map(row -> QuantityScaleGuard.productIdOf(row.getStockItemId()))
                .toList());

        List<LocationInventoryItemsResponse.Item> items = rows.stream()
                .map(row -> LocationInventoryItemsResponse.Item.builder()
                        .stockItemId(row.getStockItemId())
                        .onHandQuantity(row.getOnHand())
                        .unitOfMeasure(uomByProductId.get(QuantityScaleGuard.productIdOf(row.getStockItemId())))
                        .build())
                .toList();

        return LocationInventoryItemsResponse.builder()
                .locationId(locationId)
                .items(items)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryInquiryResponse getLocationInventoryAsOf(
            @NonNull UUID locationId, @Nullable String sku, @NonNull Instant asOf) {
        asOfQueryGuard.check(asOf);

        BigDecimal onHand = (sku == null || sku.isBlank())
                ? inventoryLedgerEntryRepository.calculateOnHandAtLocationAsOf(
                        locationId, InventoryLedgerEventType.onHandAffectingTypes(), asOf)
                : inventoryLedgerEntryRepository.calculateOnHandForStockItemAtLocationAsOf(
                        sku, locationId, InventoryLedgerEventType.onHandAffectingTypes(), asOf);

        // On-hand only: availableToPromiseQuantity stays null — historical allocation state
        // is not reliably reconstructable from ATP-neutral ledger events (A3, #1029).
        return LocationInventoryInquiryResponse.builder()
                .locationId(locationId)
                .onHandQuantity(Quantities.nz(onHand))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryItemsResponse listLocationInventoryItemsAsOf(
            @NonNull UUID locationId, @NonNull Instant asOf) {
        asOfQueryGuard.check(asOf);

        List<InventoryLedgerEntryRepository.LocationOnHand> rows =
                inventoryLedgerEntryRepository.findPositiveOnHandByLocationAsOf(
                        locationId, InventoryLedgerEventType.onHandAffectingTypes(), asOf);
        // One batched IN query for the whole page instead of one BaseUnitOfMeasureResolver round
        // trip per row.
        Map<UUID, String> uomByProductId = baseUnitOfMeasureResolver.resolveAll(rows.stream()
                .map(row -> QuantityScaleGuard.productIdOf(row.getStockItemId()))
                .toList());

        List<LocationInventoryItemsResponse.Item> items = rows.stream()
                .map(row -> LocationInventoryItemsResponse.Item.builder()
                        .stockItemId(row.getStockItemId())
                        .onHandQuantity(row.getOnHandQuantity())
                        .unitOfMeasure(uomByProductId.get(QuantityScaleGuard.productIdOf(row.getStockItemId())))
                        .build())
                .toList();

        return LocationInventoryItemsResponse.builder()
                .locationId(locationId)
                .items(items)
                .build();
    }
}
