package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Aggregates on-hand inventory at the location level from inventory ledger entries.
 */
@Service
public class LocationInventoryInquiryServiceImpl implements LocationInventoryInquiryService {

    private static final List<InventoryLedgerEventType> ON_HAND_EVENT_TYPES = Arrays.stream(
            InventoryLedgerEventType.values())
            .filter(InventoryLedgerEventType::affectsOnHand)
            .toList();

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public LocationInventoryInquiryServiceImpl(InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    @NonNull
    public LocationInventoryInquiryResponse getLocationInventory(@NonNull UUID locationId) {
        Integer onHandQuantity = inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                locationId,
                ON_HAND_EVENT_TYPES);

        return LocationInventoryInquiryResponse.builder()
                .locationId(locationId)
                .onHandQuantity(onHandQuantity == null ? 0 : onHandQuantity)
                .build();
    }
}
