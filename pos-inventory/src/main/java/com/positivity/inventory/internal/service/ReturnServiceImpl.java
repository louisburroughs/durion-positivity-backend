package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.returns.ReasonCodeDto;
import com.positivity.inventory.internal.dto.returns.ReturnItemLine;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnLineDto;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryReturnEntity;
import com.positivity.inventory.internal.entity.InventoryReturnLineEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryReturnRepository;
import com.positivity.inventory.service.ReturnService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ReturnServiceImpl implements ReturnService {

    private final InventoryReturnRepository inventoryReturnRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReturnableItemDto> listReturnableItems(@NonNull UUID workorderId) {
        // Placeholder stub until a dedicated returnable-item source-of-record is
        // introduced.
        return List.of(ReturnableItemDto.builder()
                .itemId(UUIDv7Generator.generate())
                .sku("UNKNOWN-SKU")
                .description("Returnable item placeholder")
                .quantityReturnable(0)
                .workorderId(workorderId)
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReasonCodeDto> listReturnReasonCodes() {
        return List.of(
                ReasonCodeDto.builder()
                        .code("DAMAGED")
                        .description("Item is damaged")
                        .category("DAMAGE")
                        .build(),
                ReasonCodeDto.builder()
                        .code("WRONG_ITEM")
                        .description("Incorrect item shipped")
                        .category("ERROR")
                        .build(),
                ReasonCodeDto.builder()
                        .code("EXCESS")
                        .description("Excess quantity returned")
                        .category("QUANTITY")
                        .build(),
                ReasonCodeDto.builder()
                        .code("DEFECTIVE")
                        .description("Item is defective")
                        .category("DAMAGE")
                        .build());
    }

    @Override
    @Transactional
    public @NonNull ReturnSubmissionResultDto submitToStock(@NonNull ReturnSubmitRequest request) {
        List<ReturnLineDto> lines = request.getLines() == null ? List.of() : request.getLines();
        return ReturnSubmissionResultDto.builder()
                .returnId(UUIDv7Generator.generate())
                .workorderId(request.getWorkorderId())
                .processedLines(lines.size())
                .status("SUBMITTED")
                .processedAt(Instant.now(clock))
                .build();
    }

    @Transactional
    public @NonNull ReturnResponse returnItemsToStock(@NonNull ReturnItemsRequest request) {
        if (request.getReturnReason() == null || request.getReturnReason().isBlank()) {
            throw new IllegalArgumentException("returnReason must not be blank");
        }

        List<ReturnItemLine> items = request.getItems() == null ? List.of() : request.getItems();
        for (ReturnItemLine item : items) {
            validateReturnQuantity(request.getWorkorderId(), item);
        }

        InventoryReturnEntity inventoryReturn = buildReturnEntity(request, items);
        InventoryReturnEntity savedReturn = Objects.requireNonNull(
                inventoryReturnRepository.save(inventoryReturn), "inventoryReturnRepository.save(...) returned null");

        List<InventoryLedgerEntry> ledgerEntries = new ArrayList<>();
        for (ReturnItemLine item : items) {
            ledgerEntries.add(buildReturnLedgerEntry(request, item));
        }

        List<InventoryLedgerEntry> savedLedgerEntries = inventoryLedgerEntryRepository.saveAll(ledgerEntries);
        inventoryFactPublisher.markEntries(savedLedgerEntries);
        List<UUID> ledgerEntryIds = savedLedgerEntries.stream()
                .map(InventoryLedgerEntry::getLedgerEntryId)
                .filter(Objects::nonNull)
                .toList();

        UUID returnId = savedReturn.getReturnId() != null ? savedReturn.getReturnId() : UUIDv7Generator.generate();
        Instant createdAt = savedReturn.getCreatedAt() != null ? savedReturn.getCreatedAt() : Instant.now(clock);

        return new ReturnResponse(
                returnId,
                request.getWorkorderId(),
                request.getReturnReason().trim(),
                calculateTotalItemsReturned(items),
                createdAt,
                ledgerEntryIds);
    }

    private int calculateTotalItemsReturned(List<ReturnItemLine> items) {
        return items.stream().mapToInt(ReturnItemLine::getQuantityReturned).sum();
    }

    private InventoryReturnEntity buildReturnEntity(ReturnItemsRequest request, List<ReturnItemLine> items) {
        InventoryReturnEntity inventoryReturn = InventoryReturnEntity.builder()
                .workorderId(request.getWorkorderId())
                .returnReason(request.getReturnReason().trim())
                .totalItemsReturned(calculateTotalItemsReturned(items))
                .build();

        List<InventoryReturnLineEntity> lines = new ArrayList<>();
        for (ReturnItemLine item : items) {
            InventoryReturnLineEntity line = InventoryReturnLineEntity.builder()
                    .inventoryReturn(inventoryReturn)
                    .skuId(item.getSkuId())
                    .quantityReturned(item.getQuantityReturned())
                    .build();
            lines.add(line);
        }
        inventoryReturn.setLines(lines);
        return inventoryReturn;
    }

    private InventoryLedgerEntry buildReturnLedgerEntry(ReturnItemsRequest request, ReturnItemLine item) {
        return InventoryLedgerEntry.builder()
                .stockItemId(item.getSkuId().toString())
                .eventType(InventoryLedgerEventType.RETURN_TO_STOCK)
                .changeInQuantity(Math.abs(item.getQuantityReturned()))
                .quantityAfter(0)
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .notes("Returned to stock from workorder "
                        + request.getWorkorderId()
                        + ": "
                        + request.getReturnReason())
                .build();
    }

    private void validateReturnQuantity(UUID workorderId, ReturnItemLine item) {
        if (item.getQuantityReturned() <= 0) {
            throw new IllegalArgumentException("quantityReturned must be positive");
        }

        List<InventoryLedgerEntry> consumptionEntries =
                inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        item.getSkuId().toString(),
                        InventoryLedgerEventType.WORKORDER_CONSUMPTION,
                        workorderId.toString());

        int totalConsumed = consumptionEntries.stream()
                .map(InventoryLedgerEntry::getChangeInQuantity)
                .filter(Objects::nonNull)
                .mapToInt(value -> Math.abs(value.intValue()))
                .sum();

        if (totalConsumed < item.getQuantityReturned()) {
            throw new ReturnQuantityExceededException(item.getSkuId(), item.getQuantityReturned(), totalConsumed);
        }
    }
}
