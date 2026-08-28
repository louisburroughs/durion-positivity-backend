package com.positivity.inventory.internal.tracking.service;

import com.positivity.inventory.internal.dto.serial.SerialUnitResponse;
import com.positivity.inventory.internal.entity.InventorySerialUnit;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read implementation over the serial unit master (odoo-parity E4, issue #1050). Writes go
 * exclusively through the ledger posting funnel's serial capture/consumption path
 * ({@code SerialUnitPostingService}).
 */
@Service
@RequiredArgsConstructor
public class InventorySerialUnitServiceImpl implements InventorySerialUnitService {

    private final InventorySerialUnitRepository serialRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SerialUnitResponse> listSerialUnits(
            @Nullable String stockItemId, @Nullable InventorySerialStatus status, @Nullable String serialNumber) {
        Specification<InventorySerialUnit> spec = (root, query, cb) -> cb.conjunction();
        if (stockItemId != null && !stockItemId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("stockItemId"), stockItemId.trim()));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (serialNumber != null && !serialNumber.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("serialNumber"), serialNumber.trim()));
        }
        return serialRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "serialUnitId")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SerialUnitResponse getSerialUnit(@NonNull UUID serialUnitId) {
        return toResponse(serialRepository
                .findById(serialUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("InventorySerialUnit", serialUnitId.toString())));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SerialUnitResponse findBySerial(@NonNull String stockItemId, @NonNull String serialNumber) {
        return toResponse(serialRepository
                .findByStockItemIdAndSerialNumber(stockItemId.trim(), serialNumber.trim())
                .orElseThrow(
                        () -> new ResourceNotFoundException("InventorySerialUnit", stockItemId + "/" + serialNumber)));
    }

    private SerialUnitResponse toResponse(InventorySerialUnit unit) {
        return SerialUnitResponse.builder()
                .serialUnitId(unit.getSerialUnitId())
                .stockItemId(unit.getStockItemId())
                .serialNumber(unit.getSerialNumber())
                .status(unit.getStatus())
                .locationId(unit.getLocationId())
                .lotId(unit.getLotId())
                .workorderId(unit.getWorkorderId())
                .receiptLedgerEntryId(unit.getReceiptLedgerEntryId())
                .consumptionLedgerEntryId(unit.getConsumptionLedgerEntryId())
                .receivedAt(unit.getReceivedAt())
                .consumedAt(unit.getConsumedAt())
                .createdAt(unit.getCreatedAt())
                .updatedAt(unit.getUpdatedAt())
                .build();
    }
}
