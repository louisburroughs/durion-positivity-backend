package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.DeactivateLocationResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.InventoryLocationService;
import com.positivity.security.common.SecurityContextHelper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryLocationServiceImpl implements InventoryLocationService {

    private static final String DEACTIVATED_STATUS = "Inactive";
    private static final String LOCATION_TRANSFER_REASON = "LOCATION_DEACTIVATION_TRANSFER";

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final StorageLocationValidationService storageLocationValidationService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final InventoryFactPublisher inventoryFactPublisher;

    public InventoryLocationServiceImpl(
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            StorageLocationValidationService storageLocationValidationService,
            ApplicationEventPublisher applicationEventPublisher,
            MeterRegistry meterRegistry,
            InventoryFactPublisher inventoryFactPublisher,
            Clock clock) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.storageLocationValidationService = storageLocationValidationService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DeactivateLocationResponse deactivateLocation(UUID locationId, UUID destinationLocationId) {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId is required");
        }

        StorageLocationValidationService.StorageLocationValidation sourceValidation =
                validateSourceLocation(locationId);
        List<InventoryLedgerEntryRepository.LocationOnHand> onHandRows =
                inventoryLedgerEntryRepository.findPositiveOnHandByLocation(
                        locationId, InventoryLedgerEventType.onHandAffectingTypes());
        boolean hasStock = !onHandRows.isEmpty();

        UUID resolvedDestinationLocationId = destinationLocationId;
        if (hasStock) {
            StorageLocationValidationService.StorageLocationValidation destinationValidation =
                    validateDestinationLocation(locationId, sourceValidation, destinationLocationId);
            resolvedDestinationLocationId = destinationValidation.getStorageLocationId();
        }

        List<DeactivateLocationResponse.MovedItem> movedItems =
                hasStock ? transferAllStock(locationId, resolvedDestinationLocationId, onHandRows) : List.of();

        DeactivateLocationResponse response = new DeactivateLocationResponse();
        response.setSourceLocationId(locationId);
        response.setDestinationLocationId(resolvedDestinationLocationId);
        response.setStatus(DEACTIVATED_STATUS);
        if (!movedItems.isEmpty()) {
            DeactivateLocationResponse.Transfer transfer = new DeactivateLocationResponse.Transfer();
            transfer.setMovedItems(movedItems);
            transfer.setMovedAt(OffsetDateTime.now(clock));
            response.setTransfer(transfer);
        }

        publishAuditEvent(locationId, resolvedDestinationLocationId, movedItems);
        publishMetrics(hasStock, movedItems.size());

        return response;
    }

    private StorageLocationValidationService.StorageLocationValidation validateSourceLocation(UUID locationId) {
        StorageLocationValidationService.StorageLocationValidation sourceValidation =
                storageLocationValidationService.getStorageLocationValidation(locationId.toString());
        if (!sourceValidation.isExists()) {
            throw new LocationNotFoundException(locationId);
        }
        if (!sourceValidation.isActive()) {
            throw new IllegalStateException("Source location is inactive: " + locationId);
        }
        return sourceValidation;
    }

    private StorageLocationValidationService.StorageLocationValidation validateDestinationLocation(
            UUID sourceLocationId,
            StorageLocationValidationService.StorageLocationValidation sourceValidation,
            UUID destinationLocationId) {
        if (destinationLocationId == null) {
            throw new IllegalArgumentException("destinationLocationId is required when source has stock");
        }
        if (sourceLocationId.equals(destinationLocationId)) {
            throw new IllegalArgumentException("destinationLocationId must be different from locationId");
        }

        StorageLocationValidationService.StorageLocationValidation destinationValidation =
                storageLocationValidationService.getStorageLocationValidation(destinationLocationId.toString());
        if (!destinationValidation.isExists()) {
            throw new LocationNotFoundException(destinationLocationId);
        }
        if (!destinationValidation.isActive()) {
            throw new IllegalStateException("Destination location is inactive: " + destinationLocationId);
        }

        UUID sourceSiteId = sourceValidation.getSiteId();
        UUID destinationSiteId = destinationValidation.getSiteId();
        if (sourceSiteId == null || destinationSiteId == null || !sourceSiteId.equals(destinationSiteId)) {
            throw new IllegalArgumentException("destinationLocationId must belong to the same site as source location");
        }
        return destinationValidation;
    }

    private List<DeactivateLocationResponse.MovedItem> transferAllStock(
            UUID sourceLocationId,
            UUID destinationLocationId,
            List<InventoryLedgerEntryRepository.LocationOnHand> onHandRows) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
        Instant timestamp = Instant.now(clock);

        List<InventoryLedgerEntry> transferEntries = new ArrayList<>(onHandRows.size() * 2);
        List<DeactivateLocationResponse.MovedItem> movedItems = new ArrayList<>(onHandRows.size());

        for (InventoryLedgerEntryRepository.LocationOnHand row : onHandRows) {
            int quantityToMove = Math.toIntExact(row.getOnHandQuantity());
            if (quantityToMove <= 0) {
                continue;
            }

            String stockItemId = row.getStockItemId();
            int destinationOnHand = safeQuantity(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                    stockItemId, destinationLocationId));

            transferEntries.add(InventoryLedgerEntry.builder()
                    .stockItemId(stockItemId)
                    .locationId(sourceLocationId)
                    .fromLocationId(sourceLocationId)
                    .toLocationId(destinationLocationId)
                    .eventType(InventoryLedgerEventType.TRANSFER_OUT)
                    .changeInQuantity(-quantityToMove)
                    .quantityAfter(0)
                    .transactionUserId(actorUserId)
                    .timestamp(timestamp)
                    .reasonCode(LOCATION_TRANSFER_REASON)
                    .notes("Stock moved due to location deactivation")
                    .build());

            transferEntries.add(InventoryLedgerEntry.builder()
                    .stockItemId(stockItemId)
                    .locationId(destinationLocationId)
                    .fromLocationId(sourceLocationId)
                    .toLocationId(destinationLocationId)
                    .eventType(InventoryLedgerEventType.TRANSFER_IN)
                    .changeInQuantity(quantityToMove)
                    .quantityAfter(destinationOnHand + quantityToMove)
                    .transactionUserId(actorUserId)
                    .timestamp(timestamp)
                    .reasonCode(LOCATION_TRANSFER_REASON)
                    .notes("Stock received from deactivated location")
                    .build());

            DeactivateLocationResponse.MovedItem movedItem = new DeactivateLocationResponse.MovedItem();
            movedItem.setItemId(stockItemId);
            movedItem.setQuantity(quantityToMove);
            movedItems.add(movedItem);
        }

        if (!transferEntries.isEmpty()) {
            inventoryLedgerEntryRepository.saveAll(transferEntries);
            inventoryFactPublisher.markEntries(transferEntries);
        }
        return movedItems;
    }

    private void publishAuditEvent(
            UUID sourceLocationId, UUID destinationLocationId, List<DeactivateLocationResponse.MovedItem> movedItems) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "InventoryLocationDeactivated");
        event.put("sourceLocationId", sourceLocationId.toString());
        event.put("destinationLocationId", destinationLocationId == null ? null : destinationLocationId.toString());
        event.put("movedItemCount", movedItems.size());
        event.put("occurredAt", Instant.now(clock).toString());
        applicationEventPublisher.publishEvent(event);
    }

    private void publishMetrics(boolean hasStock, int movedItemCount) {
        meterRegistry.counter("inventory.location.deactivate.total").increment();
        if (hasStock) {
            meterRegistry
                    .counter("inventory.location.deactivate.transferred.total")
                    .increment();
        }
        if (movedItemCount > 0) {
            meterRegistry
                    .counter("inventory.location.deactivate.items_moved.total")
                    .increment(movedItemCount);
        }
    }

    private int safeQuantity(Integer value) {
        return value == null ? 0 : value;
    }
}
