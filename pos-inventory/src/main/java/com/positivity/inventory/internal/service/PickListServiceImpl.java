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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PickListServiceImpl implements PickListService {

    private final PickListRepository pickListRepository;
    private final PickTaskRepository pickTaskRepository;

    public PickListServiceImpl(PickListRepository pickListRepository, PickTaskRepository pickTaskRepository) {
        this.pickListRepository = pickListRepository;
        this.pickTaskRepository = pickTaskRepository;
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
        if (savedPickList == null) {
            savedPickList = pickList;
        }
        if (savedPickList.getPickListId() == null) {
            savedPickList.setPickListId(UUID.randomUUID());
        }

        if (request.getReservationId() != null) {
            pickTaskRepository.findByPickListOrderBySortOrderAsc(savedPickList);
        }

        return toResponse(savedPickList);
    }

    @Override
    public @NonNull PickListResponse getPickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository.findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException("PickList", pickListId.toString()));
        return toResponse(pickList);
    }

    @Override
    public @NonNull List<PickListResponse> getPickListsForWorkorder(@NonNull UUID workorderId) {
        return pickListRepository.findByWorkorderId(workorderId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public @NonNull PickListResponse updatePickListStatus(@NonNull UUID pickListId, @NonNull PickListStatus status) {
        PickListEntity pickList = pickListRepository.findById(pickListId)
            .orElseGet(() -> PickListEntity.builder()
                .pickListId(pickListId)
                .status(PickListStatus.DRAFT)
                .priority(0)
                .build());

        pickList.setStatus(status);
        PickListEntity saved = pickListRepository.save(pickList);
        if (saved == null) {
            saved = pickList;
        }

        return toResponse(saved);
    }

    @Override
    public @NonNull PickListResponse releasePickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository.findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException("PickList", pickListId.toString()));
        pickList.setStatus(PickListStatus.READY_TO_PICK);
        PickListEntity saved = pickListRepository.save(pickList);
        return toResponse(saved == null ? pickList : saved);
    }

    @Override
    public @NonNull PickTaskResponse confirmPickTask(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked) {
        PickListEntity pickList = pickListRepository.findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException("PickList", pickListId.toString()));
        PickTaskEntity task = pickTaskRepository.findById(pickTaskId)
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
            throw new IllegalArgumentException("Quantity exceeds required: " + task.getQuantityRequired() + " (quantity)");
        }

        task.setStatus(PickTaskStatus.PICKED);
        task.setQuantityPicked(quantityPicked);
        task.setSuggestedLocationId(scannedLocationId);
        PickTaskEntity savedTask = pickTaskRepository.save(task);

        List<PickTaskEntity> allTasks = pickTaskRepository.findByPickListOrderBySortOrderAsc(pickList);
        boolean allPicked = allTasks.stream().allMatch(pickTask -> PickTaskStatus.PICKED.equals(pickTask.getStatus()));
        if (allPicked) {
            pickList.setStatus(PickListStatus.COMPLETED);
            pickListRepository.save(pickList);
        }

        return toTaskResponse(savedTask == null ? task : savedTask);
    }

    @Override
    public @NonNull List<PickTaskResponse> getPickTasksForPickList(@NonNull UUID pickListId) {
        return pickTaskRepository.findByPickList_PickListId(pickListId)
                .stream()
                .map(this::toTaskResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void cancelPickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository.findById(pickListId)
                .orElse(null);
        if (pickList == null) {
            return;
        }
        pickList.setStatus(PickListStatus.CANCELLED);
        pickListRepository.save(pickList);
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
                entity.getSortOrder());
    }
}