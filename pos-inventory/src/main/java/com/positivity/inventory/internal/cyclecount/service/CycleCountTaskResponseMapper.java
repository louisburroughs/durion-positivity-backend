package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single {@link CycleCountTask} → {@link CycleCountTaskResponse} mapping, shared by
 * {@code CycleCountServiceImpl} and {@code CycleCountTaskGenerationServiceImpl} so a new response
 * field is added in one place instead of two diverging private copies.
 */
@Component
@RequiredArgsConstructor
public class CycleCountTaskResponseMapper {

    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    /** Maps one task, resolving its base UoM per call. */
    public @NonNull CycleCountTaskResponse toResponse(@NonNull CycleCountTask task) {
        return toResponse(task, baseUnitOfMeasureResolver.resolve(task.getItemSku()));
    }

    /**
     * Maps one task with a pre-resolved base UoM ({@code null} when the SKU resolves to none) —
     * for batch callers that memoize the resolver lookup per SKU.
     */
    public @NonNull CycleCountTaskResponse toResponse(@NonNull CycleCountTask task, @Nullable String unitOfMeasure) {
        return CycleCountTaskResponse.builder()
                .taskId(task.getTaskId())
                .binLocation(task.getBinLocation())
                .itemSku(task.getItemSku())
                .itemDescription(task.getItemDescription())
                .expectedQuantity(task.getExpectedQuantity())
                .unitOfMeasure(unitOfMeasure)
                .auditorId(task.getAuditorId())
                .planId(task.getPlanId())
                .status(task.getStatus())
                .latestCountEntryId(task.getLatestCountEntryId())
                .countEntriesCount(task.getCountEntriesCount())
                .createdAt(
                        task.getCreatedAt() != null
                                ? LocalDateTime.ofInstant(task.getCreatedAt(), ZoneOffset.UTC)
                                : null)
                .updatedAt(
                        task.getUpdatedAt() != null
                                ? LocalDateTime.ofInstant(task.getUpdatedAt(), ZoneOffset.UTC)
                                : null)
                .build();
    }
}
