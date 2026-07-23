package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.lot.LotDetailResponse;
import com.positivity.inventory.internal.dto.lot.LotResponse;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.service.InventoryLotService;
import java.util.Comparator;
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
 * Read implementation over the lot master (odoo-parity E1, issue #1038). Per-location on-hand
 * is served from the per-lot {@code inventory_stock_summary} rows — never by aggregating the
 * ledger at read time.
 */
@Service
@RequiredArgsConstructor
public class InventoryLotServiceImpl implements InventoryLotService {

    private final InventoryLotRepository lotRepository;
    private final InventoryStockSummaryRepository summaryRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<LotResponse> listLots(
            @Nullable String stockItemId, @Nullable InventoryLotStatus status, @Nullable String lotNumber) {
        Specification<InventoryLot> spec = (root, query, cb) -> cb.conjunction();
        if (stockItemId != null && !stockItemId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("stockItemId"), stockItemId.trim()));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (lotNumber != null && !lotNumber.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("lotNumber"), lotNumber.trim()));
        }
        return lotRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "receivedAt", "lotId")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull LotDetailResponse getLot(@NonNull UUID lotId) {
        InventoryLot lot = lotRepository
                .findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLot", lotId.toString()));
        List<LotDetailResponse.LotLocationOnHand> locations = summaryRepository.findByLotId(lotId).stream()
                .sorted(Comparator.comparing(row ->
                        row.getLocationId() == null ? "" : row.getLocationId().toString()))
                .map(this::toLocationOnHand)
                .toList();
        return LotDetailResponse.builder()
                .lot(toResponse(lot))
                .locations(locations)
                .build();
    }

    private LotDetailResponse.LotLocationOnHand toLocationOnHand(InventoryStockSummary row) {
        return LotDetailResponse.LotLocationOnHand.builder()
                .locationId(row.getLocationId())
                .onHand(row.getOnHand())
                .inTransitQty(row.getInTransitQty())
                .build();
    }

    private LotResponse toResponse(InventoryLot lot) {
        return LotResponse.builder()
                .lotId(lot.getLotId())
                .stockItemId(lot.getStockItemId())
                .lotNumber(lot.getLotNumber())
                .receivedAt(lot.getReceivedAt())
                .vendorId(lot.getVendorId())
                .expirationDate(lot.getExpirationDate())
                .status(lot.getStatus())
                .createdAt(lot.getCreatedAt())
                .updatedAt(lot.getUpdatedAt())
                .build();
    }
}
