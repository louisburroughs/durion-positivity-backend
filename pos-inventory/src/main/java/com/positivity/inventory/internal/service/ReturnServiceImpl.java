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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReturnServiceImpl implements ReturnService {

    private final InventoryReturnRepository inventoryReturnRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final DocumentQuantityConverter documentQuantityConverter;
    private final Clock clock;
    private final @Nullable InventoryLotOutboundService lotOutboundService;

    @Autowired
    public ReturnServiceImpl(
            InventoryReturnRepository inventoryReturnRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            DocumentQuantityConverter documentQuantityConverter,
            Clock clock,
            InventoryLotOutboundService lotOutboundService) {
        this.inventoryReturnRepository = inventoryReturnRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.documentQuantityConverter = documentQuantityConverter;
        this.clock = clock;
        this.lotOutboundService = lotOutboundService;
    }

    /**
     * Lot-gate-less constructor kept for the pre-E2 unit-test fixtures: without an
     * {@link InventoryLotOutboundService} every SKU behaves untracked (no lot validation,
     * lot-null postings) — identical to the service's behavior for NONE-tracked products
     * (odoo-parity E2, issue #1042).
     */
    public ReturnServiceImpl(
            InventoryReturnRepository inventoryReturnRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            DocumentQuantityConverter documentQuantityConverter,
            Clock clock) {
        this(
                inventoryReturnRepository,
                inventoryLedgerEntryRepository,
                ledgerPostingService,
                inventoryFactPublisher,
                documentQuantityConverter,
                clock,
                null);
    }

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
        // odoo-parity B2 (#1034): convert optional document-UoM quantities to base BEFORE the
        // consumed-quantity validation and the ledger posting.
        List<ReturnLineComputation> computedItems = new ArrayList<>(items.size());
        for (ReturnItemLine item : items) {
            computedItems.add(computeReturnLine(item));
        }
        for (ReturnLineComputation computed : computedItems) {
            validateReturnQuantity(request.getWorkorderId(), computed);
        }

        InventoryReturnEntity inventoryReturn = buildReturnEntity(request, computedItems);
        InventoryReturnEntity savedReturn = Objects.requireNonNull(
                inventoryReturnRepository.save(inventoryReturn), "inventoryReturnRepository.save(...) returned null");

        List<InventoryLedgerEntry> ledgerEntries = new ArrayList<>();
        for (ReturnLineComputation computed : computedItems) {
            ledgerEntries.add(buildReturnLedgerEntry(request, computed));
        }

        List<InventoryLedgerEntry> savedLedgerEntries = ledgerPostingService.postAll(ledgerEntries);
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
                calculateTotalItemsReturned(computedItems),
                createdAt,
                ledgerEntryIds);
    }

    /**
     * Per-line return derivation (odoo-parity B2, #1034): the optional document-UoM conversion
     * plus the whole base quantity that validates against consumption and posts to the ledger.
     * {@code lotId} (odoo-parity E2, #1042) is the resolved lot for LOT-tracked SKUs — a
     * return must name an EXISTING lot ({@code LOT_NUMBER_REQUIRED}/{@code LOT_UNKNOWN}), but
     * unlike the other outbound-flow validations its status is not gated: returning stock to a
     * CONSUMED lot is the normal way it comes back to life (the funnel's status reconciler
     * flips CONSUMED → ACTIVE once the quantity lands), and returned units of a
     * QUARANTINED/RECALLED lot belong on that lot's balance where the block keeps applying.
     */
    private record ReturnLineComputation(
            ReturnItemLine item,
            DocumentQuantityConverter.DocumentConversion conversion,
            int baseQuantity,
            @Nullable UUID lotId) {}

    private ReturnLineComputation computeReturnLine(ReturnItemLine item) {
        DocumentQuantityConverter.DocumentConversion conversion = documentQuantityConverter
                .convertIfPresent(
                        item.getSkuId(),
                        String.valueOf(item.getSkuId()),
                        item.getDocumentUom(),
                        item.getDocumentQuantity())
                .orElse(null);
        int baseQuantity;
        if (conversion == null) {
            baseQuantity = item.getQuantityReturned();
        } else {
            try {
                baseQuantity = conversion.baseQuantity().intValueExact();
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "converted base quantity must be a whole number within 32-bit integer range", ex);
            }
        }
        if (baseQuantity <= 0) {
            throw new IllegalArgumentException("quantityReturned must be positive");
        }
        UUID lotId = lotOutboundService == null
                ? null
                : lotOutboundService.resolveReturnLot(item.getSkuId().toString(), item.getLotNumber());
        return new ReturnLineComputation(item, conversion, baseQuantity, lotId);
    }

    private int calculateTotalItemsReturned(List<ReturnLineComputation> items) {
        return items.stream().mapToInt(ReturnLineComputation::baseQuantity).sum();
    }

    private InventoryReturnEntity buildReturnEntity(ReturnItemsRequest request, List<ReturnLineComputation> items) {
        InventoryReturnEntity inventoryReturn = InventoryReturnEntity.builder()
                .workorderId(request.getWorkorderId())
                .returnReason(request.getReturnReason().trim())
                .totalItemsReturned(calculateTotalItemsReturned(items))
                .build();

        List<InventoryReturnLineEntity> lines = new ArrayList<>();
        for (ReturnLineComputation computed : items) {
            DocumentQuantityConverter.DocumentConversion conversion = computed.conversion();
            InventoryReturnLineEntity line = InventoryReturnLineEntity.builder()
                    .inventoryReturn(inventoryReturn)
                    .skuId(computed.item().getSkuId())
                    .quantityReturned(computed.baseQuantity())
                    .documentUom(conversion == null ? null : conversion.documentUom())
                    .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                    .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                    .build();
            lines.add(line);
        }
        inventoryReturn.setLines(lines);
        return inventoryReturn;
    }

    private InventoryLedgerEntry buildReturnLedgerEntry(ReturnItemsRequest request, ReturnLineComputation computed) {
        return InventoryLedgerEntry.builder()
                .stockItemId(computed.item().getSkuId().toString())
                .eventType(InventoryLedgerEventType.RETURN_TO_STOCK)
                .changeInQuantity(Math.abs(computed.baseQuantity()))
                .quantityAfter(0)
                .lotId(computed.lotId())
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .notes("Returned to stock from workorder "
                        + request.getWorkorderId()
                        + ": "
                        + request.getReturnReason())
                .build();
    }

    private void validateReturnQuantity(UUID workorderId, ReturnLineComputation computed) {
        List<InventoryLedgerEntry> consumptionEntries =
                inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        computed.item().getSkuId().toString(),
                        InventoryLedgerEventType.WORKORDER_CONSUMPTION,
                        workorderId.toString());

        int totalConsumed = consumptionEntries.stream()
                .map(InventoryLedgerEntry::getChangeInQuantity)
                .filter(Objects::nonNull)
                .mapToInt(value -> Math.abs(value.intValue()))
                .sum();

        if (totalConsumed < computed.baseQuantity()) {
            throw new ReturnQuantityExceededException(
                    computed.item().getSkuId(), computed.baseQuantity(), totalConsumed);
        }
    }
}
