package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.model.InventoryLedgerEntry;
import com.positivity.inventory.internal.model.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.InventoryAvailabilityService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class InventoryAvailabilityServiceImpl implements InventoryAvailabilityService {

    private static final String DEFAULT_LOCATION = "DEFAULT";

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public InventoryAvailabilityServiceImpl(InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId) {
        // Issue #48: Resolve per-location on-hand and ATP from ledger entries.
        List<InventoryLedgerEntry> ledgerEntries = inventoryLedgerEntryRepository
                .findByStockItemIdOrderByTimestampAsc(productId.toString());

        if (ledgerEntries.isEmpty()) {
            return List.of();
        }

        Map<String, List<InventoryLedgerEntry>> entriesByLocation = ledgerEntries.stream()
                .collect(Collectors.groupingBy(this::resolveLocationId));

        return entriesByLocation.entrySet().stream()
                .map(entry -> toLocationAvailability(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(LocationAvailabilityDto::getLocationId))
                .toList();
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
                .mapToInt(InventoryLedgerEntry::getChangeInQuantity)
                .sum();

        int activeReservations = entries.stream()
                .mapToInt(this::reservationDelta)
                .sum();

        int atpQuantity = Math.max(onHandQuantity - activeReservations, 0);

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
            case RESERVATION_CREATED, ALLOCATION_CREATED -> ledgerEntry.getChangeInQuantity();
            case RESERVATION_RELEASED, ALLOCATION_RELEASED -> -ledgerEntry.getChangeInQuantity();
            default -> 0;
        };
    }
}
