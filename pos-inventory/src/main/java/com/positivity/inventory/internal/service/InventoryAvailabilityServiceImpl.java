package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventorySourceType;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.service.InventoryAvailabilityService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory availability service implementation backed by the
 * {@code inventory_stock_summary} read model (issue #1024, A1). Quantities
 * come from the stored summary maintained by {@code LedgerPostingService};
 * only the derived unit of measure still consults the ledger (single-row
 * lookup). Response shapes are unchanged from the ledger-aggregation era.
 *
 * Issue: CAP-170 (#48)
 */
@Service
@Slf4j
public class InventoryAvailabilityServiceImpl implements InventoryAvailabilityService {

    private static final Pageable FIRST_ONLY = PageRequest.of(0, 1);
    private static final String DEFAULT_UOM = "EACH";

    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public InventoryAvailabilityServiceImpl(
            InventoryStockSummaryRepository stockSummaryRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.stockSummaryRepository = stockSummaryRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId) {
        if (productId == null) {
            throw new InvalidInventoryAvailabilityRequestException("Product ID is required");
        }

        List<InventoryStockSummary> summaryRows;
        try {
            summaryRows = stockSummaryRepository.findByStockItemId(productId.toString());
        } catch (Exception ex) {
            log.error("Failed retrieving inventory availability summary for product {}", productId, ex);
            throw new IllegalStateException("Unable to retrieve inventory availability at this time", ex);
        }

        return summaryRows.stream()
                .filter(row -> row.getLocationId() != null)
                .sorted(Comparator.comparing(InventoryStockSummary::getLocationId))
                .map(this::toLocationAvailability)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityView queryAvailability(
            @NonNull String productSku,
            @Nullable UUID locationId,
            @Nullable UUID storageLocationId,
            @Nullable InventorySourceType sourceType) {
        List<InventoryStockSummary> productRows = stockSummaryRepository.findByStockItemId(productSku);
        if (productRows.isEmpty()) {
            throw new ProductNotFoundException(productSku);
        }

        UUID scopeLocationId = storageLocationId != null ? storageLocationId : locationId;
        long onHand;
        long allocated;
        if (scopeLocationId == null) {
            onHand = productRows.stream()
                    .mapToLong(InventoryStockSummary::getOnHand)
                    .sum();
            allocated = productRows.stream()
                    .mapToLong(InventoryStockSummary::getAllocated)
                    .sum();
        } else {
            InventoryStockSummary scoped = productRows.stream()
                    .filter(row -> scopeLocationId.equals(row.getLocationId()))
                    .findFirst()
                    .orElse(null);
            onHand = scoped == null ? 0L : scoped.getOnHand();
            allocated = scoped == null ? 0L : scoped.getAllocated();
        }

        return AvailabilityView.builder()
                .productSku(productSku)
                .locationId(locationId)
                .storageLocationId(storageLocationId)
                .onHandQuantity(Math.toIntExact(onHand))
                .allocatedQuantity(Math.toIntExact(allocated))
                .availableToPromiseQuantity(Math.toIntExact(onHand - allocated))
                .unitOfMeasure(deriveUnitOfMeasure(productSku, scopeLocationId))
                .build();
    }

    private String deriveUnitOfMeasure(String productSku, @Nullable UUID scopeLocationId) {
        List<String> units = scopeLocationId == null
                ? inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItem(productSku, FIRST_ONLY)
                : inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        productSku, scopeLocationId, FIRST_ONLY);
        return units.isEmpty() ? DEFAULT_UOM : units.getFirst();
    }

    private LocationAvailabilityDto toLocationAvailability(InventoryStockSummary row) {
        // Pre-#1024 behavior: this per-location list subtracts BOTH hard
        // allocations and soft reservations from ATP (unlike queryAvailability,
        // which per ADR-0001 subtracts allocations only).
        long atpWithReservations = row.getOnHand() - row.getAllocated() - row.getReserved();
        return LocationAvailabilityDto.builder()
                .locationId(row.getLocationId())
                .locationName(row.getLocationId().toString())
                .onHandQuantity(Math.toIntExact(row.getOnHand()))
                .availableToPromiseQuantity(Math.toIntExact(atpWithReservations))
                .build();
    }
}
