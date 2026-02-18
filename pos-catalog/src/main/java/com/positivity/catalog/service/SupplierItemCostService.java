package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.CostTierDto;
import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostResponseDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import com.positivity.catalog.internal.entity.CostTierEntity;
import com.positivity.catalog.internal.entity.SupplierItemCostEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.SupplierItemCostRepository;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierItemCostService {

    private static final String AND_ITEM_ID = " and itemId=";
    private static final String SUPPLIER_ITEM_COST_NOT_FOUND_FOR_SUPPLIER_ID = "Supplier item cost not found for supplierId=";
    private final SupplierItemCostRepository supplierItemCostRepository;

    public SupplierItemCostService(SupplierItemCostRepository supplierItemCostRepository) {
        this.supplierItemCostRepository = supplierItemCostRepository;
    }

    @Transactional
    public SupplierItemCostResponseDto createSupplierItemCost(@NonNull SupplierItemCostCreateRequestDto request) {
        validateCreateRequest(request);
        if (supplierItemCostRepository.existsBySupplierIdAndItemId(request.getSupplierId(), request.getItemId())) {
            throw new CatalogBusinessRuleException(
                    "DUPLICATE_SUPPLIER_ITEM_COST: Supplier and item already have a cost structure.");
        }

        SupplierItemCostEntity entity = new SupplierItemCostEntity();
        entity.setSupplierId(request.getSupplierId());
        entity.setItemId(request.getItemId());
        entity.setCurrencyCode(normalizeCurrencyCode(request.getCurrencyCode()));
        entity.setBaseCost(request.getBaseCost());
        entity.setCostTiers(toTierEntities(request.getTiers()));

        return toResponse(supplierItemCostRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public SupplierItemCostResponseDto getSupplierItemCost(@NonNull UUID supplierId, @NonNull UUID itemId) {
        return supplierItemCostRepository.findBySupplierIdAndItemId(supplierId, itemId)
                .map(this::toResponse)
                .orElseThrow(() -> new CatalogNotFoundException(
                        SUPPLIER_ITEM_COST_NOT_FOUND_FOR_SUPPLIER_ID + supplierId + AND_ITEM_ID + itemId));
    }

    @Transactional
    public SupplierItemCostResponseDto updateSupplierItemCost(
            @NonNull UUID supplierId,
            @NonNull UUID itemId,
            @NonNull SupplierItemCostUpdateRequestDto request) {
        validateUpdateRequest(request);
        SupplierItemCostEntity existing = supplierItemCostRepository.findBySupplierIdAndItemId(supplierId, itemId)
                .orElseThrow(() -> new CatalogNotFoundException(
                        SUPPLIER_ITEM_COST_NOT_FOUND_FOR_SUPPLIER_ID + supplierId + AND_ITEM_ID + itemId));
        if (!request.getVersion().equals(existing.getVersion())) {
            throw new OptimisticLockException("Version mismatch for supplier-item cost update.");
        }

        existing.setCurrencyCode(normalizeCurrencyCode(request.getCurrencyCode()));
        existing.setBaseCost(request.getBaseCost());
        existing.setCostTiers(toTierEntities(request.getTiers()));

        return toResponse(supplierItemCostRepository.save(existing));
    }

    @Transactional
    public void deleteSupplierItemCost(@NonNull UUID supplierId, @NonNull UUID itemId) {
        SupplierItemCostEntity existing = supplierItemCostRepository.findBySupplierIdAndItemId(supplierId, itemId)
                .orElseThrow(() -> new CatalogNotFoundException(
                        SUPPLIER_ITEM_COST_NOT_FOUND_FOR_SUPPLIER_ID + supplierId + AND_ITEM_ID + itemId));
        supplierItemCostRepository.delete(existing);
    }

    private void validateCreateRequest(SupplierItemCostCreateRequestDto request) {
        if (request.getSupplierId() == null) {
            throw new CatalogValidationException("INVALID_DATA: supplierId is required.");
        }
        if (request.getItemId() == null) {
            throw new CatalogValidationException("INVALID_DATA: itemId is required.");
        }
        validateCurrencyCode(request.getCurrencyCode());
        validateBaseCost(request.getBaseCost());
        validateTierStructure(request.getTiers());
    }

    private void validateUpdateRequest(SupplierItemCostUpdateRequestDto request) {
        if (request.getVersion() == null) {
            throw new CatalogValidationException("INVALID_DATA: version is required.");
        }
        validateCurrencyCode(request.getCurrencyCode());
        validateBaseCost(request.getBaseCost());
        validateTierStructure(request.getTiers());
    }

    private void validateCurrencyCode(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new CatalogValidationException("INVALID_DATA: currencyCode is required.");
        }
        String normalized = normalizeCurrencyCode(currencyCode);
        if (normalized.length() != 3) {
            throw new CatalogValidationException("INVALID_DATA: currencyCode must be a 3-letter ISO code.");
        }
    }

    private void validateBaseCost(BigDecimal baseCost) {
        if (baseCost != null && baseCost.signum() < 0) {
            throw new CatalogValidationException("INVALID_DATA: baseCost cannot be negative.");
        }
    }

    private void validateTierStructure(List<CostTierDto> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }

        List<CostTierDto> sortedTiers = sortTiersByMinQuantity(tiers);

        int expectedMin = 1;
        for (int index = 0; index < sortedTiers.size(); index++) {
            CostTierDto tier = sortedTiers.get(index);
            Integer minQuantity = tier.getMinQuantity();
            Integer maxQuantity = tier.getMaxQuantity();
            validateTierRequiredFields(tier);
            validateTierBounds(minQuantity, maxQuantity);
            validateTierContinuity(minQuantity, expectedMin);
            validateOpenEndedTierPlacement(maxQuantity, index, sortedTiers.size());

            if (maxQuantity == null) {
                return;
            }
            expectedMin = maxQuantity + 1;
        }

        throw new CatalogValidationException("INVALID_TIER_STRUCTURE: Final tier must have null maxQuantity.");
    }

    private List<CostTierDto> sortTiersByMinQuantity(List<CostTierDto> tiers) {
        return tiers.stream()
                .sorted(Comparator.comparing(CostTierDto::getMinQuantity, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private void validateTierRequiredFields(CostTierDto tier) {
        Integer minQuantity = tier.getMinQuantity();
        BigDecimal unitCost = tier.getUnitCost();

        if (minQuantity == null || minQuantity < 1) {
            throw new CatalogValidationException("INVALID_DATA: min_quantity must be >= 1.");
        }
        if (unitCost == null || unitCost.signum() <= 0) {
            throw new CatalogValidationException("INVALID_DATA: unit_cost must be a positive value.");
        }
    }

    private void validateTierBounds(Integer minQuantity, Integer maxQuantity) {
        if (maxQuantity != null && minQuantity > maxQuantity) {
            throw new CatalogValidationException(
                    "INVALID_TIER_STRUCTURE: minQuantity must be less than or equal to maxQuantity.");
        }
    }

    private void validateTierContinuity(Integer minQuantity, int expectedMin) {
        if (minQuantity < expectedMin) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: Quantity ranges overlap.");
        }
        if (minQuantity > expectedMin) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: Quantity ranges must be contiguous.");
        }
    }

    private void validateOpenEndedTierPlacement(Integer maxQuantity, int index, int tierCount) {
        if (maxQuantity == null && index < tierCount - 1) {
            throw new CatalogValidationException(
                    "INVALID_TIER_STRUCTURE: Only the final tier can have null maxQuantity.");
        }
    }

    private List<CostTierEntity> toTierEntities(List<CostTierDto> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }

        List<CostTierDto> sortedTiers = new ArrayList<>(tiers);
        sortedTiers.sort(Comparator.comparing(CostTierDto::getMinQuantity));

        List<CostTierEntity> entities = new ArrayList<>(sortedTiers.size());
        for (int index = 0; index < sortedTiers.size(); index++) {
            CostTierDto dto = sortedTiers.get(index);
            CostTierEntity entity = new CostTierEntity();
            entity.setMinQuantity(dto.getMinQuantity());
            entity.setMaxQuantity(dto.getMaxQuantity());
            entity.setUnitCost(dto.getUnitCost());
            entity.setTierOrderIndex(index + 1);
            entities.add(entity);
        }
        return entities;
    }

    private SupplierItemCostResponseDto toResponse(SupplierItemCostEntity entity) {
        SupplierItemCostResponseDto dto = new SupplierItemCostResponseDto();
        dto.setId(entity.getId());
        dto.setSupplierId(entity.getSupplierId());
        dto.setItemId(entity.getItemId());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setBaseCost(entity.getBaseCost());
        dto.setVersion(entity.getVersion());

        List<CostTierDto> tiers = entity.getCostTiers().stream()
                .sorted(Comparator.comparing(CostTierEntity::getMinQuantity))
                .map(tierEntity -> {
                    CostTierDto tierDto = new CostTierDto();
                    tierDto.setMinQuantity(tierEntity.getMinQuantity());
                    tierDto.setMaxQuantity(tierEntity.getMaxQuantity());
                    tierDto.setUnitCost(tierEntity.getUnitCost());
                    return tierDto;
                })
                .toList();
        dto.setTiers(tiers);
        return dto;
    }

    private String normalizeCurrencyCode(String currencyCode) {
        return currencyCode.trim().toUpperCase(Locale.ROOT);
    }
}
