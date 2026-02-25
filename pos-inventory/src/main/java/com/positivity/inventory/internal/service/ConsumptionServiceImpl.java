package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.positivity.inventory.service.ConsumptionService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConsumptionServiceImpl implements ConsumptionService {

    private final PickTaskRepository pickTaskRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public ConsumptionServiceImpl(
            PickTaskRepository pickTaskRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.pickTaskRepository = pickTaskRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    public @NonNull ConsumptionResponse consumePickedItems(@NonNull ConsumeItemsRequest request) {
        if (request.getWorkorderId() == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }

        List<ConsumeItemLine> items = request.getItems() == null ? List.of() : request.getItems();

        List<InventoryLedgerEntry> entriesToSave = new ArrayList<>();
        for (ConsumeItemLine item : items) {
            PickTaskEntity task = pickTaskRepository.findById(item.getPickTaskId())
                    .orElseThrow(() -> new ResourceNotFoundException("PickTask", item.getPickTaskId().toString()));

            if (task.getStatus() != PickTaskStatus.PICKED) {
                throw new WorkorderConsumptionException("Item not picked: " + item.getPickTaskId());
            }

            if (item.getQuantity() > task.getQuantityPicked()) {
                throw new WorkorderConsumptionException("Requested quantity exceeds picked quantity for task: "
                        + item.getPickTaskId());
            }

            entriesToSave.add(buildLedgerEntry(request, item));
        }

        List<InventoryLedgerEntry> savedEntries = inventoryLedgerEntryRepository.saveAll(entriesToSave);
        List<UUID> ledgerEntryIds = savedEntries.stream()
                .map(InventoryLedgerEntry::getLedgerEntryId)
                .filter(Objects::nonNull)
                .toList();

        int totalItemsConsumed = items.stream().mapToInt(ConsumeItemLine::getQuantity).sum();

        return new ConsumptionResponse(
                UUID.randomUUID(),
                request.getWorkorderId(),
                request.getPickListId(),
                totalItemsConsumed,
                Instant.now(),
                ledgerEntryIds);
    }

    private InventoryLedgerEntry buildLedgerEntry(ConsumeItemsRequest request, ConsumeItemLine item) {
        return InventoryLedgerEntry.builder()
                .stockItemId(item.getSkuId() == null ? "" : item.getSkuId().toString())
                .eventType(InventoryLedgerEventType.WORKORDER_CONSUMPTION)
                .changeInQuantity(-Math.abs(item.getQuantity()))
                .quantityAfter(0)
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .timestamp(Instant.now())
                .notes("Consumed from pick task " + item.getPickTaskId() + " for workorder " + request.getWorkorderId())
                .build();
    }
}
