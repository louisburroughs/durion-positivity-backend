package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Location-level on-hand inquiry served from the {@code inventory_stock_summary}
 * read model (issue #1024, A1) instead of aggregating the ledger per request.
 */
@Service
public class LocationInventoryInquiryServiceImpl implements LocationInventoryInquiryService {

    private final InventoryStockSummaryRepository stockSummaryRepository;

    public LocationInventoryInquiryServiceImpl(InventoryStockSummaryRepository stockSummaryRepository) {
        this.stockSummaryRepository = stockSummaryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryInquiryResponse getLocationInventory(@NonNull UUID locationId, @Nullable String sku) {
        long onHandQuantity;
        long outstandingAllocations;
        if (sku == null || sku.isBlank()) {
            onHandQuantity = stockSummaryRepository.sumOnHandAtLocation(locationId);
            outstandingAllocations = stockSummaryRepository.sumAllocatedAtLocation(locationId);
        } else {
            InventoryStockSummary row = stockSummaryRepository
                    .findByStockItemIdAndLocationId(sku, locationId)
                    .orElse(null);
            onHandQuantity = row == null ? 0L : row.getOnHand();
            outstandingAllocations = row == null ? 0L : row.getAllocated();
        }

        return LocationInventoryInquiryResponse.builder()
                .locationId(locationId)
                .onHandQuantity(onHandQuantity)
                .availableToPromiseQuantity(onHandQuantity - outstandingAllocations)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationInventoryItemsResponse listLocationInventoryItems(@NonNull UUID locationId) {
        List<LocationInventoryItemsResponse.Item> items =
                stockSummaryRepository.findByLocationIdAndOnHandGreaterThan(locationId, 0L).stream()
                        .map(row -> LocationInventoryItemsResponse.Item.builder()
                                .stockItemId(row.getStockItemId())
                                .onHandQuantity(row.getOnHand())
                                .build())
                        .toList();

        return LocationInventoryItemsResponse.builder()
                .locationId(locationId)
                .items(items)
                .build();
    }
}
