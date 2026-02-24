package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.InventoryAvailabilityService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inventory availability service implementation backed by inventory ledger
 * entries.
 *
 * Issue: CAP-170 (#48)
 */
@Service
@Slf4j
public class InventoryAvailabilityServiceImpl implements InventoryAvailabilityService {

    private static final String DEFAULT_LOCATION = "DEFAULT";

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public InventoryAvailabilityServiceImpl(InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId) {
        if (productId == null) {
            throw new InvalidInventoryAvailabilityRequestException("Product ID is required");
        }

        // Issue #48: Resolve per-location on-hand and ATP from ledger entries.
        List<InventoryLedgerEntry> ledgerEntries;
        try {
            ledgerEntries = inventoryLedgerEntryRepository.findByStockItemIdOrderByTimestampAsc(productId.toString());
        } catch (Exception ex) {
            log.error("Failed retrieving inventory availability ledger entries for product {}", productId, ex);
            throw new IllegalStateException("Unable to retrieve inventory availability at this time", ex);
        }

        if (ledgerEntries.isEmpty()) {
            return List.of();
        }

        Map<String, List<InventoryLedgerEntry>> entriesByLocation = ledgerEntries.stream()
                .filter(this::isProcessableEntry)
                .collect(Collectors.groupingBy(this::resolveLocationId));

        return entriesByLocation.entrySet().stream()
                .map(entry -> toLocationAvailability(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(LocationAvailabilityDto::getLocationId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityView queryAvailability(
            @NonNull String productSku,
            @NonNull String locationId,
            @Nullable String storageLocationId) {
        List<InventoryLedgerEntry> allProductEntries = inventoryLedgerEntryRepository
                .findByStockItemIdOrderByTimestampAsc(productSku);
        if (allProductEntries.isEmpty()) {
            throw new ProductNotFoundException(productSku);
        }

        String scopeLocationId = (storageLocationId != null && !storageLocationId.isBlank())
                ? storageLocationId
                : locationId;
        List<InventoryLedgerEntry> locationEntries = inventoryLedgerEntryRepository
                .findByStockItemIdAndLocationIdOrderByTimestampAsc(
                        productSku, scopeLocationId);

        int onHand = locationEntries.stream()
                .filter(e -> e.getEventType() != null && e.getEventType().affectsOnHand())
                .mapToInt(e -> e.getChangeInQuantity() != null ? e.getChangeInQuantity() : 0)
                .sum();

        int allocationCreated = locationEntries.stream()
                .filter(e -> e.getEventType() == InventoryLedgerEventType.ALLOCATION_CREATED)
                .mapToInt(e -> e.getChangeInQuantity() != null ? e.getChangeInQuantity() : 0)
                .sum();
        int allocationReleased = locationEntries.stream()
                .filter(e -> e.getEventType() == InventoryLedgerEventType.ALLOCATION_RELEASED)
                .mapToInt(e -> e.getChangeInQuantity() != null ? e.getChangeInQuantity() : 0)
                .sum();
        int allocatedQty = allocationCreated - allocationReleased;

        return AvailabilityView.builder()
                .productSku(productSku)
                .locationId(locationId)
                .storageLocationId(storageLocationId != null && !storageLocationId.isBlank()
                        ? storageLocationId
                        : null)
                .onHandQuantity(onHand)
                .allocatedQuantity(allocatedQty)
                .availableToPromiseQuantity(onHand - allocatedQty)
                .unitOfMeasure("EACH")
                .build();
    }

    private boolean isProcessableEntry(InventoryLedgerEntry ledgerEntry) {
        if (ledgerEntry == null) {
            log.warn("Skipping null inventory ledger entry while computing availability");
            return false;
        }
        if (ledgerEntry.getChangeInQuantity() == null) {
            log.warn("Skipping inventory ledger entry {} with null changeInQuantity",
                    ledgerEntry.getLedgerEntryId());
            return false;
        }
        return true;
    }

    private String resolveLocationId(InventoryLedgerEntry ledgerEntry) {
        if (ledgerEntry.getLocationId() == null || ledgerEntry.getLocationId().isBlank()) {
            return DEFAULT_LOCATION;
        }
        return ledgerEntry.getLocationId();
    }

    private LocationAvailabilityDto toLocationAvailability(String locationId, List<InventoryLedgerEntry> entries) {
        int onHandQuantity = entries.stream()
                .filter(ledgerEntry -> ledgerEntry.getEventType() != null && ledgerEntry.getEventType().affectsOnHand())
                .mapToInt(ledgerEntry -> ledgerEntry.getChangeInQuantity() != null ? ledgerEntry.getChangeInQuantity()
                        : 0)
                .sum();

        int activeReservations = entries.stream()
                .mapToInt(this::reservationDelta)
                .sum();

        int atpQuantity = onHandQuantity - activeReservations;

        return LocationAvailabilityDto.builder()
                .locationId(locationId)
                .locationName(locationId)
                .onHandQuantity(onHandQuantity)
                .availableToPromiseQuantity(atpQuantity)
                .build();
    }

    private int reservationDelta(InventoryLedgerEntry ledgerEntry) {
        InventoryLedgerEventType eventType = ledgerEntry.getEventType();
        if (eventType == null) {
            return 0;
        }

        return switch (eventType) {
            case RESERVATION_CREATED, ALLOCATION_CREATED -> safeQuantity(ledgerEntry);
            case RESERVATION_RELEASED, ALLOCATION_RELEASED -> -safeQuantity(ledgerEntry);
            default -> 0;
        };
    }

    private int safeQuantity(InventoryLedgerEntry ledgerEntry) {
        Integer quantity = ledgerEntry.getChangeInQuantity();
        if (quantity == null) {
            log.warn("Ledger entry {} has null changeInQuantity; treating as 0",
                    ledgerEntry.getLedgerEntryId());
            return 0;
        }
        return quantity;
    }
}
