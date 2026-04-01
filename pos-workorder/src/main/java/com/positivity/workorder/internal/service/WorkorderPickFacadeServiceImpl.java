package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.client.InventoryPickClient;
import com.positivity.workorder.internal.client.InventoryPickClient.InventoryConfirmPickTaskRequest;
import com.positivity.workorder.internal.client.InventoryPickClient.InventoryConsumeItemLine;
import com.positivity.workorder.internal.client.InventoryPickClient.InventoryConsumeItemsRequest;
import com.positivity.workorder.internal.client.InventoryPickClient.InventoryConsumptionDto;
import com.positivity.workorder.internal.client.InventoryPickClient.InventoryPickListDto;
import com.positivity.workorder.internal.client.InventoryPickClient.InventoryPickTaskDto;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsResponse;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickedItemResponse;
import com.positivity.workorder.internal.enums.ConsumeItemStatus;
import com.positivity.workorder.service.WorkorderPickFacadeService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderPickFacadeServiceImpl implements WorkorderPickFacadeService {

  private static final String STATUS_MATCHED = "MATCHED";
  private static final String STATUS_LOCATION_MISMATCH = "LOCATION_MISMATCH";
  private static final String STATUS_SKU_MISMATCH = "SKU_MISMATCH";
  private static final String STATUS_NO_MATCH = "NO_MATCH";
  private static final String STATUS_PICKED = "PICKED";

  private final InventoryPickClient inventoryPickClient;

  @Override
  @NonNull
  public WorkorderPickListResponse getPickListForWorkorder(@NonNull UUID workorderId) {
    try {
      InventoryPickListDto pickList = resolvePrimaryPickList(workorderId);
      return mapPickList(pickList);
    } catch (Exception e) {
      throw toResponseStatusException("getPickListForWorkorder", workorderId, null, null, e);
    }
  }

  @Override
  @NonNull
  public List<WorkorderPickTaskResponse> getPickTasksForWorkorder(@NonNull UUID workorderId) {
    try {
      InventoryPickListDto pickList = resolvePrimaryPickList(workorderId);
      return inventoryPickClient.getPickTasksForPickList(pickList.pickListId()).stream()
          .map(this::mapPickTask)
          .toList();
    } catch (Exception e) {
      throw toResponseStatusException("getPickTasksForWorkorder", workorderId, null, null, e);
    }
  }

  @Override
  @NonNull
  public ResolveScanResponse resolveScan(
      @NonNull UUID workorderId,
      @NonNull UUID pickTaskId,
      @NonNull ResolveScanRequest request) {
    try {
      InventoryPickListDto pickList = resolvePrimaryPickList(workorderId);
      List<InventoryPickTaskDto> tasks = inventoryPickClient.getPickTasksForPickList(pickList.pickListId());
      InventoryPickTaskDto task = resolveTask(tasks, pickTaskId);

      boolean skuMatches = task.skuId().equals(request.getScannedSkuId());
      boolean locationMatches = task.locationId().equals(request.getScannedLocationId());

      boolean matched = skuMatches && locationMatches;
      String matchStatus;
      if (matched) {
        matchStatus = STATUS_MATCHED;
      } else if (skuMatches) {
        matchStatus = STATUS_LOCATION_MISMATCH;
      } else if (locationMatches) {
        matchStatus = STATUS_SKU_MISMATCH;
      } else {
        matchStatus = STATUS_NO_MATCH;
      }

      return ResolveScanResponse.builder()
          .pickTaskId(task.pickTaskId())
          .pickListId(task.pickListId())
          .resolvedSkuId(request.getScannedSkuId())
          .resolvedLocationId(request.getScannedLocationId())
          .matched(matched)
          .matchStatus(matchStatus)
          .build();
    } catch (Exception e) {
      throw toResponseStatusException("resolveScan", workorderId, null, pickTaskId, e);
    }
  }

  @Override
  @NonNull
  public WorkorderPickTaskResponse confirmPickLine(
      @NonNull UUID workorderId,
      @NonNull UUID pickTaskId,
      @NonNull UUID pickLineId,
      @NonNull ConfirmPickLineRequest request) {
    try {
      InventoryPickListDto pickList = resolvePrimaryPickList(workorderId);
      List<InventoryPickTaskDto> tasks = inventoryPickClient.getPickTasksForPickList(pickList.pickListId());
      InventoryPickTaskDto task = resolveTask(tasks, pickTaskId);

      if (!pickLineId.equals(pickTaskId)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "pickLineId " + pickLineId + " does not match pickTaskId " + pickTaskId);
      }

      // Inventory tasks are the atomic unit; facade pickLineId aliases pickTaskId.
      InventoryConfirmPickTaskRequest confirmRequest = new InventoryConfirmPickTaskRequest(
          task.skuId(),
          task.locationId(),
          request.getQuantityPicked());

      InventoryPickTaskDto confirmed = inventoryPickClient.confirmPickTask(pickList.pickListId(), task.pickTaskId(),
          confirmRequest);

      return mapPickTask(confirmed);
    } catch (Exception e) {
      throw toResponseStatusException("confirmPickLine", workorderId, null, pickTaskId, e);
    }
  }

  @Override
  @NonNull
  public WorkorderPickTaskResponse completePickTask(
      @NonNull UUID workorderId,
      @NonNull UUID pickTaskId,
      @NonNull CompletePickTaskRequest request) {
    try {
      InventoryPickListDto pickList = resolvePrimaryPickList(workorderId);
      List<InventoryPickTaskDto> tasks = inventoryPickClient.getPickTasksForPickList(pickList.pickListId());
      InventoryPickTaskDto task = resolveTask(tasks, pickTaskId);

      int remaining = task.quantityRequired() - task.quantityPicked();
      if (remaining <= 0) {
        return mapPickTask(task);
      }

      // Send total required quantity so pos-inventory sets quantityPicked = quantityRequired,
      // completing the task. Sending only `remaining` would under-report picks when
      // quantityPicked > 0 (e.g. picked=2/required=3 → remaining=1 → inventory sets picked to 1).
      InventoryConfirmPickTaskRequest confirmRequest = new InventoryConfirmPickTaskRequest(
          task.skuId(),
          task.locationId(),
          task.quantityRequired());

      InventoryPickTaskDto confirmed = inventoryPickClient.confirmPickTask(pickList.pickListId(), task.pickTaskId(),
          confirmRequest);
      return mapPickTask(confirmed);
    } catch (Exception e) {
      throw toResponseStatusException("completePickTask", workorderId, null, pickTaskId, e);
    }
  }

  @Override
  @NonNull
  public List<WorkorderPickedItemResponse> getPickedItemsForWorkorder(@NonNull UUID workorderId) {
    try {
      List<InventoryPickListDto> pickLists = inventoryPickClient.getPickListsForWorkorder(workorderId);
      if (pickLists.isEmpty()) {
        return List.of();
      }

      InventoryPickListDto pickList = pickLists.getFirst();
      return inventoryPickClient.getPickTasksForPickList(pickList.pickListId()).stream()
          .filter(task -> STATUS_PICKED.equalsIgnoreCase(task.status()) || task.quantityPicked() > 0)
          .map(task -> WorkorderPickedItemResponse.builder()
              .pickTaskId(task.pickTaskId())
              .pickListId(task.pickListId())
              .skuId(task.skuId())
              .qtyPicked(task.quantityPicked())
              .qtyConsumed(0)
              .qtyRemaining(task.quantityPicked())
              .status(task.status())
              .build())
          .toList();
    } catch (Exception e) {
      throw toResponseStatusException("getPickedItemsForWorkorder", workorderId, null, null, e);
    }
  }

  @Override
  @NonNull
  public ConsumePickedItemsResponse consumePickedItems(
      @NonNull UUID workorderId,
      @NonNull ConsumePickedItemsRequest request) {
    try {
      List<InventoryPickListDto> pickLists = inventoryPickClient.getPickListsForWorkorder(workorderId);
      if (pickLists.isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No pick list found for workorder " + workorderId);
      }

      InventoryPickListDto pickList = pickLists.getFirst();
      List<InventoryPickTaskDto> tasks = inventoryPickClient.getPickTasksForPickList(pickList.pickListId());
      Map<UUID, InventoryPickTaskDto> taskMap = tasks.stream()
          .collect(Collectors.toMap(InventoryPickTaskDto::pickTaskId, t -> t));

      List<InventoryConsumeItemLine> inventoryItems = request.getItems().stream()
          .map(item -> {
            InventoryPickTaskDto task = taskMap.get(item.getPickTaskId());
            if (task == null) {
              throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                  "Pick task not found: " + item.getPickTaskId());
            }
            return new InventoryConsumeItemLine(item.getPickTaskId(), task.skuId(), item.getQuantityToConsume());
          })
          .toList();

      InventoryConsumeItemsRequest consumeRequest = new InventoryConsumeItemsRequest(workorderId, pickList.pickListId(),
          inventoryItems);

      InventoryConsumptionDto consumptionDto = inventoryPickClient.consumePickedItems(consumeRequest);

      List<ConsumePickedItemsResponse.ConsumedItemResult> results = request.getItems().stream()
          .map(item -> ConsumePickedItemsResponse.ConsumedItemResult.builder()
              .pickTaskId(item.getPickTaskId())
              .quantityConsumed(item.getQuantityToConsume())
              .status(ConsumeItemStatus.SUCCESS)
              .build())
          .toList();

      return ConsumePickedItemsResponse.builder()
          .workorderId(workorderId)
          .totalItemsConsumed(consumptionDto.totalItemsConsumed())
          .results(results)
          .build();
    } catch (Exception e) {
      throw toResponseStatusException("consumePickedItems", workorderId, null, null, e);
    }
  }

  private InventoryPickListDto resolvePrimaryPickList(UUID workorderId) {
    List<InventoryPickListDto> pickLists = inventoryPickClient.getPickListsForWorkorder(workorderId);
    if (pickLists.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "No pick list found for workorder " + workorderId);
    }
    return pickLists.getFirst();
  }

  private InventoryPickTaskDto resolveTask(List<InventoryPickTaskDto> tasks, UUID pickTaskId) {
    return tasks.stream()
        .filter(task -> task.pickTaskId().equals(pickTaskId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Pick task not found: " + pickTaskId));
  }

  private WorkorderPickListResponse mapPickList(InventoryPickListDto pickList) {
    return WorkorderPickListResponse.builder()
        .pickListId(pickList.pickListId())
        .workorderId(pickList.workorderId())
        .status(pickList.status())
        .priority(pickList.priority())
        .dueAt(pickList.dueAt())
        .createdAt(pickList.createdAt())
        .updatedAt(pickList.updatedAt())
        .build();
  }

  private WorkorderPickTaskResponse mapPickTask(InventoryPickTaskDto task) {
    int remainingQty = task.quantityRequired() - task.quantityPicked();
    // version: reserved for future optimistic locking; always 0L until
    // pos-inventory exposes a version field.
    long version = 0L;

    return WorkorderPickTaskResponse.builder()
        .pickTaskId(task.pickTaskId())
        .pickListId(task.pickListId())
        .skuId(task.skuId())
        .locationId(task.locationId())
        .requiredQty(task.quantityRequired())
        .pickedQty(task.quantityPicked())
        .remainingQty(remainingQty)
        .status(task.status())
        .sortOrder(task.sortOrder())
        .version(version)
        .build();
  }

  private ResponseStatusException toResponseStatusException(
      String operation,
      UUID workorderId,
      UUID pickListId,
      UUID pickTaskId,
      Exception exception) {
    if (exception instanceof ResponseStatusException responseStatusException) {
      log.warn(
          "Workorder pick facade {} failed (workorderId={}, pickListId={}, pickTaskId={}, status={}): {}",
          operation,
          workorderId,
          pickListId,
          pickTaskId,
          responseStatusException.getStatusCode(),
          responseStatusException.getReason(),
          exception);
      return responseStatusException;
    }

    if (exception instanceof RestClientResponseException restClientResponseException) {
      ResponseStatusException mapped = new ResponseStatusException(
          restClientResponseException.getStatusCode(),
          restClientResponseException.getStatusText(),
          restClientResponseException);
      log.warn(
          "Workorder pick facade {} failed with upstream response (workorderId={}, pickListId={}, pickTaskId={}, status={}): {}",
          operation,
          workorderId,
          pickListId,
          pickTaskId,
          restClientResponseException.getStatusCode(),
          restClientResponseException.getMessage(),
          restClientResponseException);
      return mapped;
    }

    if (exception instanceof RestClientException restClientException) {
      log.warn(
          "Workorder pick facade {} failed with upstream client error (workorderId={}, pickListId={}, pickTaskId={}): {}",
          operation,
          workorderId,
          pickListId,
          pickTaskId,
          restClientException.getMessage(),
          restClientException);
      return new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "Inventory service request failed",
          restClientException);
    }

    if (exception instanceof IllegalStateException illegalStateException) {
      log.warn(
          "Workorder pick facade {} failed with illegal state (workorderId={}, pickListId={}, pickTaskId={}): {}",
          operation,
          workorderId,
          pickListId,
          pickTaskId,
          illegalStateException.getMessage(),
          illegalStateException);
      return new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          illegalStateException.getMessage(),
          illegalStateException);
    }

    log.warn(
        "Workorder pick facade {} failed unexpectedly (workorderId={}, pickListId={}, pickTaskId={}): {}",
        operation,
        workorderId,
        pickListId,
        pickTaskId,
        exception.getMessage(),
        exception);
    return new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Unexpected workorder pick facade failure",
        exception);
  }
}