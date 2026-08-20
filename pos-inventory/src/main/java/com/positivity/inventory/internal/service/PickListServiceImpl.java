package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.service.PickListService;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PickListServiceImpl implements PickListService {

    private static final String PICK_LIST = "PickList";
    private final PickListRepository pickListRepository;
    private final PickTaskRepository pickTaskRepository;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final @Nullable InventoryLotOutboundService lotOutboundService;
    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    @Autowired
    public PickListServiceImpl(
            PickListRepository pickListRepository,
            PickTaskRepository pickTaskRepository,
            InventoryFactPublisher inventoryFactPublisher,
            InventoryLotOutboundService lotOutboundService,
            BaseUnitOfMeasureResolver baseUnitOfMeasureResolver) {
        this.pickListRepository = pickListRepository;
        this.pickTaskRepository = pickTaskRepository;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.lotOutboundService = lotOutboundService;
        this.baseUnitOfMeasureResolver = baseUnitOfMeasureResolver;
    }

    /**
     * Lot-gate-less constructor kept for the pre-E2 unit-test fixtures: without an
     * {@link InventoryLotOutboundService} every SKU behaves untracked (no lot validation,
     * null {@code pickedLotId}) — identical to the service's behavior for NONE-tracked
     * products, which is all those fixtures exercise.
     */
    public PickListServiceImpl(
            PickListRepository pickListRepository,
            PickTaskRepository pickTaskRepository,
            InventoryFactPublisher inventoryFactPublisher,
            BaseUnitOfMeasureResolver baseUnitOfMeasureResolver) {
        this(pickListRepository, pickTaskRepository, inventoryFactPublisher, null, baseUnitOfMeasureResolver);
    }

    @Override
    public @NonNull PickListResponse createPickList(@NonNull CreatePickListRequest request) {
        if (request.getWorkorderId() == null) {
            throw new IllegalArgumentException("workorderId is required");
        }

        PickListEntity pickList = PickListEntity.builder()
                .workorderId(request.getWorkorderId())
                .status(PickListStatus.DRAFT)
                .priority(request.getPriority())
                .dueAt(request.getDueAt())
                .build();

        PickListEntity savedPickList = pickListRepository.save(pickList);
        if (savedPickList.getPickListId() == null) {
            savedPickList.setPickListId(UUIDv7Generator.generate());
        }
        inventoryFactPublisher.markPickListChanged(savedPickList.getPickListId());

        if (request.getReservationId() != null) {
            pickTaskRepository.findByPickListOrderBySortOrderAsc(savedPickList);
        }

        return toResponse(savedPickList);
    }

    @Override
    public @NonNull PickListResponse getPickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository
                .findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException(PICK_LIST, pickListId.toString()));
        return toResponse(pickList);
    }

    @Override
    public @NonNull List<PickListResponse> getPickListsForWorkorder(@NonNull UUID workorderId) {
        return pickListRepository.findByWorkorderId(workorderId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public @NonNull PickListResponse updatePickListStatus(@NonNull UUID pickListId, @NonNull PickListStatus status) {
        PickListEntity pickList = pickListRepository
                .findById(pickListId)
                .orElseGet(() -> PickListEntity.builder()
                        .pickListId(pickListId)
                        .status(PickListStatus.DRAFT)
                        .priority(0)
                        .build());

        pickList.setStatus(status);
        PickListEntity saved = pickListRepository.save(pickList);
        inventoryFactPublisher.markPickListChanged(saved.getPickListId());
        return toResponse(saved);
    }

    @Override
    public @NonNull PickListResponse releasePickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository
                .findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException(PICK_LIST, pickListId.toString()));
        pickList.setStatus(PickListStatus.READY_TO_PICK);
        PickListEntity saved = pickListRepository.save(pickList);
        inventoryFactPublisher.markPickListChanged(saved.getPickListId());
        return toResponse(saved);
    }

    @Override
    public @NonNull PickTaskResponse confirmPickTask(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked,
            @Nullable String lotNumber) {
        PickListEntity pickList = pickListRepository
                .findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException(PICK_LIST, pickListId.toString()));
        PickTaskEntity task = pickTaskRepository
                .findById(pickTaskId)
                .orElseThrow(() -> new ResourceNotFoundException("PickTask", pickTaskId.toString()));

        if (task.getPickList() == null
                || task.getPickList().getPickListId() == null
                || !pickListId.equals(task.getPickList().getPickListId())) {
            throw new ResourceNotFoundException("PickTask", pickTaskId.toString());
        }

        if (!scannedSkuId.equals(task.getProductId())) {
            throw new PickScanMismatchException(task.getProductId(), scannedSkuId);
        }

        if (quantityPicked > task.getQuantityRequired()) {
            throw new IllegalArgumentException(
                    "Quantity exceeds required: " + task.getQuantityRequired() + " (quantity)");
        }

        // odoo-parity E2 (#1042): LOT-tracked SKUs must key the lot the units were taken from
        // (422 LOT_NUMBER_REQUIRED / LOT_UNKNOWN / LOT_NOT_AVAILABLE); the suggestion recorded
        // at generation is advisory only — the keyed lot wins and is what consumption posts.
        // Untracked SKUs resolve to null, byte-identical to pre-E2. Pick confirmation itself
        // posts no ledger entries; the pickedLotId travels to the consumption posting.
        UUID pickedLotId = lotOutboundService == null
                ? null
                : lotOutboundService.resolveOutboundLot(task.getProductId().toString(), lotNumber);

        task.setStatus(PickTaskStatus.PICKED);
        task.setQuantityPicked(quantityPicked);
        UUID actualLocationId = scannedLocationId;
        task.setSuggestedLocationId(actualLocationId);
        task.setPickedLotId(pickedLotId);
        PickTaskEntity savedTask = pickTaskRepository.save(task);
        inventoryFactPublisher.markPickTaskChanged(savedTask.getPickTaskId());

        List<PickTaskEntity> allTasks = pickTaskRepository.findByPickListOrderBySortOrderAsc(pickList);
        boolean allPicked = allTasks.stream().allMatch(pickTask -> PickTaskStatus.PICKED.equals(pickTask.getStatus()));
        if (allPicked) {
            pickList.setStatus(PickListStatus.COMPLETED);
            pickListRepository.save(pickList);
            inventoryFactPublisher.markPickListChanged(pickListId);
        }

        return toTaskResponse(savedTask);
    }

    @Override
    public @NonNull List<PickTaskResponse> getPickTasksForPickList(@NonNull UUID pickListId) {
        return pickTaskRepository.findByPickList_PickListId(pickListId).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    @Override
    public void cancelPickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository.findById(pickListId).orElse(null);
        if (pickList == null) {
            return;
        }
        pickList.setStatus(PickListStatus.CANCELLED);
        pickListRepository.save(pickList);
        inventoryFactPublisher.markPickListChanged(pickListId);
    }

    private PickListResponse toResponse(PickListEntity entity) {
        return new PickListResponse(
                entity.getPickListId(),
                entity.getWorkorderId(),
                entity.getStatus(),
                entity.getPriority(),
                entity.getDueAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private PickTaskResponse toTaskResponse(PickTaskEntity entity) {
        return new PickTaskResponse(
                entity.getPickTaskId(),
                entity.getPickList() == null ? null : entity.getPickList().getPickListId(),
                entity.getProductId(),
                entity.getSuggestedLocationId(),
                entity.getQuantityRequired(),
                entity.getQuantityPicked(),
                entity.getStatus(),
                entity.getSortOrder(),
                entity.getSuggestedLotNumber(),
                entity.getPickedLotId(),
                baseUnitOfMeasureResolver.resolve(entity.getProductId()));
    }
}
