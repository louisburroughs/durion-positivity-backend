package com.positivity.workorder.internal.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryPickClient {

    private static final ParameterizedTypeReference<List<InventoryPickListDto>> PICK_LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<InventoryPickTaskDto>> PICK_TASK_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient inventoryServiceRestClient;

    @NonNull
    public InventoryPickListDto getPickList(@NonNull UUID pickListId) {
        log.debug("Fetching pick list {} from pos-inventory", pickListId);

        InventoryPickListDto response = inventoryServiceRestClient
                .get()
                .uri("/inventory/v1/inventory/pick-lists/{pickListId}", pickListId)
                .retrieve()
                .body(InventoryPickListDto.class);

        if (response == null) {
            throw new IllegalStateException("Inventory service returned an empty response for pick list " + pickListId);
        }

        return response;
    }

    @NonNull
    public List<InventoryPickListDto> getPickListsForWorkorder(@NonNull UUID workorderId) {
        log.debug("Fetching pick lists from pos-inventory for workorder {}", workorderId);

        List<InventoryPickListDto> response = inventoryServiceRestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/inventory/v1/inventory/pick-lists")
                        .queryParam("workorderId", workorderId)
                        .build())
                .retrieve()
                .body(PICK_LIST_RESPONSE_TYPE);

        if (response == null) {
            throw new IllegalStateException(
                    "Inventory service returned an empty pick list collection for workorder " + workorderId);
        }

        return response;
    }

    @NonNull
    public InventoryPickListDto releasePickList(@NonNull UUID pickListId) {
        log.debug("Releasing pick list {} via pos-inventory", pickListId);

        InventoryPickListDto response = inventoryServiceRestClient
                .post()
                .uri("/inventory/v1/inventory/pick-lists/{pickListId}/release", pickListId)
                .retrieve()
                .body(InventoryPickListDto.class);

        if (response == null) {
            throw new IllegalStateException(
                    "Inventory service returned an empty response when releasing pick list " + pickListId);
        }

        return response;
    }

    @NonNull
    public List<InventoryPickTaskDto> getPickTasksForPickList(@NonNull UUID pickListId) {
        log.debug("Fetching pick tasks from pos-inventory for pick list {}", pickListId);

        List<InventoryPickTaskDto> response = inventoryServiceRestClient
                .get()
                .uri("/inventory/v1/inventory/pick-lists/{pickListId}/tasks", pickListId)
                .retrieve()
                .body(PICK_TASK_RESPONSE_TYPE);

        if (response == null) {
            throw new IllegalStateException(
                    "Inventory service returned an empty pick task collection for pick list " + pickListId);
        }

        return response;
    }

    @NonNull
    public InventoryPickTaskDto confirmPickTask(
            @NonNull UUID pickListId, @NonNull UUID taskId, @NonNull InventoryConfirmPickTaskRequest request) {
        log.debug("Confirming pick task {} for pick list {} via pos-inventory", taskId, pickListId);

        InventoryPickTaskDto response = inventoryServiceRestClient
                .post()
                .uri("/inventory/v1/inventory/pick-lists/{pickListId}/tasks/{taskId}/confirm", pickListId, taskId)
                .body(request)
                .retrieve()
                .body(InventoryPickTaskDto.class);

        if (response == null) {
            throw new IllegalStateException("Inventory service returned an empty response when confirming pick task "
                    + taskId + " for pick list " + pickListId);
        }

        return response;
    }

    public record InventoryPickListDto(
            UUID pickListId,
            UUID workorderId,
            String status,
            int priority,
            Instant dueAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record InventoryPickTaskDto(
            UUID pickTaskId,
            UUID pickListId,
            UUID skuId,
            UUID locationId,
            int quantityRequired,
            int quantityPicked,
            String status,
            int sortOrder) {}

    public record InventoryConfirmPickTaskRequest(UUID scannedSkuId, UUID scannedLocationId, int quantityPicked) {}

    public record InventoryConsumeItemLine(UUID pickTaskId, UUID skuId, int quantity) {}

    public record InventoryConsumeItemsRequest(
            UUID workorderId, UUID pickListId, List<InventoryConsumeItemLine> items) {}

    public record InventoryConsumptionDto(
            UUID consumptionId, UUID workorderId, UUID pickListId, int totalItemsConsumed) {}

    @NonNull
    public InventoryConsumptionDto consumePickedItems(@NonNull InventoryConsumeItemsRequest request) {
        log.debug(
                "Consuming picked items for workorder {} pick list {} via pos-inventory",
                request.workorderId(),
                request.pickListId());

        InventoryConsumptionDto response = inventoryServiceRestClient
                .post()
                .uri("/inventory/v1/inventory/consumption")
                .body(request)
                .retrieve()
                .body(InventoryConsumptionDto.class);

        if (response == null) {
            throw new IllegalStateException(
                    "Inventory service returned an empty response when consuming items for workorder "
                            + request.workorderId());
        }

        return response;
    }
}
