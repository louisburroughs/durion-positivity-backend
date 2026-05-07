package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.InventoryLedgerEntryDto;
import com.positivity.inventory.internal.dto.InventoryLedgerFilterParams;
import com.positivity.inventory.internal.dto.LedgerPage;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.InventoryLedgerService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryLedgerServiceImpl implements InventoryLedgerService {

    private static final Logger log = LoggerFactory.getLogger(InventoryLedgerServiceImpl.class);
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    public InventoryLedgerServiceImpl(@NonNull InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull LedgerPage<InventoryLedgerEntryDto> listLedgerEntries(@NonNull InventoryLedgerFilterParams params) {
        int pageSize = params.getPageSize() > 0 ? params.getPageSize() : DEFAULT_PAGE_SIZE;

        Specification<InventoryLedgerEntry> specification = buildSpecification(params);
        List<InventoryLedgerEntry> entries = inventoryLedgerEntryRepository
                .findAll(specification, PageRequest.of(0, pageSize + 1, Sort.by(Sort.Direction.ASC, "ledgerEntryId")))
                .getContent();

        boolean hasNextPage = entries.size() > pageSize;
        List<InventoryLedgerEntry> pageEntries = hasNextPage ? entries.subList(0, pageSize) : entries;

        List<InventoryLedgerEntryDto> dtoEntries =
                pageEntries.stream().map(this::toDto).toList();

        String nextPageToken = null;
        if (hasNextPage) {
            UUID lastLedgerEntryId = pageEntries.get(pageEntries.size() - 1).getLedgerEntryId();
            nextPageToken = encodePageToken(lastLedgerEntryId);
        }

        return LedgerPage.<InventoryLedgerEntryDto>builder()
                .entries(dtoEntries)
                .nextPageToken(nextPageToken)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull InventoryLedgerEntryDto getLedgerEntry(@NonNull UUID entryId) {
        InventoryLedgerEntry entry = inventoryLedgerEntryRepository
                .findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLedgerEntry", entryId.toString()));
        return toDto(entry);
    }

    private Specification<InventoryLedgerEntry> buildSpecification(InventoryLedgerFilterParams params) {
        List<Specification<InventoryLedgerEntry>> specifications = new ArrayList<>();
        addProductSkuFilter(params, specifications);
        addLocationFilter(params, specifications);
        addStorageLocationFilterLog(params);
        addDateFromFilter(params, specifications);
        addDateToFilter(params, specifications);
        addSourceTransactionFilter(params, specifications);
        addWorkorderFilterLog(params);
        addWorkorderLineFilterLog(params);
        addMovementTypesFilter(params, specifications);
        addPageTokenFilter(params, specifications);

        Specification<InventoryLedgerEntry> combined = (root, query, cb) -> cb.conjunction();
        for (Specification<InventoryLedgerEntry> specification : specifications) {
            combined = combined.and(specification);
        }
        return combined;
    }

    private void addProductSkuFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getProductSku() != null && !params.getProductSku().isBlank()) {
            specifications.add((root, query, cb) -> cb.equal(root.get("stockItemId"), params.getProductSku()));
        }
    }

    private void addLocationFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getLocationId() != null) {
            specifications.add((root, query, cb) -> cb.equal(root.get("locationId"), params.getLocationId()));
        }
    }

    private void addStorageLocationFilterLog(InventoryLedgerFilterParams params) {
        if (params.getStorageLocationId() != null) {
            log.debug("storageLocationId filter requested but inventory_ledger_entry has no storageLocationId column");
        }
    }

    private void addDateFromFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getDateFrom() != null) {
            specifications.add(timestampFrom(params.getDateFrom()));
        }
    }

    private void addDateToFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getDateTo() != null) {
            specifications.add(timestampTo(params.getDateTo()));
        }
    }

    private void addSourceTransactionFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getSourceTransactionId() != null) {
            specifications.add((root, query, cb) -> cb.equal(
                    root.get("sourceTransactionId"),
                    params.getSourceTransactionId().toString()));
        }
    }

    private void addWorkorderFilterLog(InventoryLedgerFilterParams params) {
        if (params.getWorkorderId() != null) {
            // Placeholder: add workorderId predicate when inventory_ledger_entry stores
            // workorderId.
            log.debug("workorderId filter requested but inventory_ledger_entry has no workorderId column");
        }
    }

    private void addWorkorderLineFilterLog(InventoryLedgerFilterParams params) {
        if (params.getWorkorderLineId() != null) {
            // Placeholder: add workorderLineId predicate when inventory_ledger_entry stores
            // workorderLineId.
            log.debug("workorderLineId filter requested but inventory_ledger_entry has no workorderLineId column");
        }
    }

    private void addMovementTypesFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getMovementTypes() == null || params.getMovementTypes().isEmpty()) {
            return;
        }

        List<InventoryLedgerEventType> eventTypes = parseMovementTypes(params.getMovementTypes());
        if (!eventTypes.isEmpty()) {
            specifications.add((root, query, cb) -> root.get("eventType").in(eventTypes));
        }
    }

    private void addPageTokenFilter(
            InventoryLedgerFilterParams params, List<Specification<InventoryLedgerEntry>> specifications) {
        if (params.getPageToken() != null && !params.getPageToken().isBlank()) {
            UUID lastSeenEntryId = decodePageToken(params.getPageToken());
            specifications.add((root, query, cb) -> cb.greaterThan(root.get("ledgerEntryId"), lastSeenEntryId));
        }
    }

    private Specification<InventoryLedgerEntry> timestampFrom(Instant dateFrom) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("timestamp"), dateFrom);
    }

    private Specification<InventoryLedgerEntry> timestampTo(Instant dateTo) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("timestamp"), dateTo);
    }

    private List<InventoryLedgerEventType> parseMovementTypes(List<String> movementTypes) {
        return movementTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toUpperCase(Locale.ROOT))
                .map(this::toInventoryLedgerEventType)
                .toList();
    }

    private InventoryLedgerEventType toInventoryLedgerEventType(String rawType) {
        try {
            return InventoryLedgerEventType.valueOf(rawType);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("Unknown movementType: " + rawType);
        }
    }

    private String encodePageToken(UUID ledgerEntryId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ledgerEntryId.toString().getBytes(StandardCharsets.UTF_8));
    }

    private UUID decodePageToken(String pageToken) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(pageToken), StandardCharsets.UTF_8);
            return UUID.fromString(decoded);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("Invalid pageToken");
        }
    }

    private InventoryLedgerEntryDto toDto(InventoryLedgerEntry entry) {
        return InventoryLedgerEntryDto.builder()
                .ledgerEntryId(entry.getLedgerEntryId())
                .stockItemId(entry.getStockItemId())
                .adjustmentId(entry.getAdjustmentId())
                .eventType(entry.getEventType())
                .changeInQuantity(entry.getChangeInQuantity())
                .quantityAfter(entry.getQuantityAfter())
                .unitCost(entry.getUnitCost())
                .transactionUserId(entry.getTransactionUserId())
                .timestamp(entry.getTimestamp())
                .locationId(entry.getLocationId())
                .fromLocationId(entry.getFromLocationId())
                .toLocationId(entry.getToLocationId())
                .reasonCode(entry.getReasonCode())
                .sourceTransactionId(entry.getSourceTransactionId())
                .unitOfMeasure(entry.getUnitOfMeasure())
                .notes(entry.getNotes())
                .createdAt(entry.getCreatedAt())
                .updatedAt(entry.getUpdatedAt())
                .build();
    }
}
