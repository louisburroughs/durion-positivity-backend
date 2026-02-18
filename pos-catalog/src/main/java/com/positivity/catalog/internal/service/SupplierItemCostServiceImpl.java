package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.CostTierDto;
import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import com.positivity.catalog.internal.entity.CostTierEntity;
import com.positivity.catalog.internal.entity.SupplierItemCostEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.SupplierItemCostRepository;
import com.positivity.catalog.service.SupplierItemCostService;
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
public class SupplierItemCostServiceImpl implements SupplierItemCostService {

    private static final String NOT_FOUND_BY_ID = "Supplier item cost not found for id=";

    private final SupplierItemCostRepository supplierItemCostRepository;

    public SupplierItemCostServiceImpl(SupplierItemCostRepository supplierItemCostRepository) {
        this.supplierItemCostRepository = supplierItemCostRepository;
    }

    @Override
    @Transactional
    public SupplierItemCostDto createCostStructure(@NonNull SupplierItemCostCreateRequestDto request) {
        validateRequest(request.getSupplierId(), request.getItemId(), request.getCurrencyCode(), request.getBaseCost(),
                request.getTiers());
        supplierItemCostRepository.findBySupplierIdAndItemId(request.getSupplierId(), request.getItemId())
                .ifPresent(existing -> {
                    throw new CatalogBusinessRuleException(
                            "DUPLICATE_SUPPLIER_ITEM_COST: supplierId and itemId already have a cost structure.");
                });

        SupplierItemCostEntity entity = new SupplierItemCostEntity();
        entity.setSupplierId(request.getSupplierId());
        entity.setItemId(request.getItemId());
        entity.setCurrencyCode(normalizeCurrencyCode(request.getCurrencyCode()));
        entity.setBaseCost(request.getBaseCost());
        entity.setCostTiers(toTierEntities(request.getTiers()));

        return toDto(supplierItemCostRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierItemCostDto getCostStructure(@NonNull UUID id) {
        return supplierItemCostRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new CatalogNotFoundException(NOT_FOUND_BY_ID + id));
    }

    @Override
    @Transactional
    public SupplierItemCostDto updateCostStructure(@NonNull UUID id,
            @NonNull SupplierItemCostUpdateRequestDto request) {
        validateRequest(request.getSupplierId(), request.getItemId(), request.getCurrencyCode(), request.getBaseCost(),
                request.getTiers());

        SupplierItemCostEntity existing = supplierItemCostRepository.findById(id)
                .orElseThrow(() -> new CatalogNotFoundException(NOT_FOUND_BY_ID + id));

        supplierItemCostRepository.findBySupplierIdAndItemId(request.getSupplierId(), request.getItemId())
                .filter(found -> !found.getId().equals(id))
                .ifPresent(found -> {
                    throw new CatalogBusinessRuleException(
                            "DUPLICATE_SUPPLIER_ITEM_COST: supplierId and itemId already have a cost structure.");
                });

        existing.setSupplierId(request.getSupplierId());
        existing.setItemId(request.getItemId());
        existing.setCurrencyCode(normalizeCurrencyCode(request.getCurrencyCode()));
        existing.setBaseCost(request.getBaseCost());
        existing.setCostTiers(toTierEntities(request.getTiers()));

        return toDto(supplierItemCostRepository.save(existing));
    }

    @Override
    @Transactional
    public void deleteCostStructure(@NonNull UUID id) {
        SupplierItemCostEntity existing = supplierItemCostRepository.findById(id)
                .orElseThrow(() -> new CatalogNotFoundException(NOT_FOUND_BY_ID + id));
        supplierItemCostRepository.delete(existing);
    }

    private void validateRequest(UUID supplierId, UUID itemId, String currencyCode, BigDecimal baseCost,
            List<CostTierDto> tiers) {
        if (supplierId == null) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: supplierId is required.");
        }
        if (itemId == null) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: itemId is required.");
        }
        if (currencyCode == null || currencyCode.isBlank() || currencyCode.trim().length() != 3) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: currencyCode must be a 3-letter ISO code.");
        }
        if (baseCost != null && baseCost.signum() < 0) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: baseCost cannot be negative.");
        }
        validateTierStructure(tiers);
    }

    private void validateTierStructure(List<CostTierDto> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }

        List<CostTierDto> sortedTiers = tiers.stream()
                .sorted(Comparator.comparing(CostTierDto::getMinQuantity, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        int expectedMinQuantity = 1;
        for (int index = 0; index < sortedTiers.size(); index++) {
            CostTierDto tier = sortedTiers.get(index);
            Integer minQuantity = tier.getMinQuantity();
            Integer maxQuantity = tier.getMaxQuantity();
            validateTierValueConstraints(tier, minQuantity, maxQuantity);
            validateTierSequenceConstraints(minQuantity, maxQuantity, expectedMinQuantity, index, sortedTiers.size());

            if (maxQuantity == null) {
                return;
            }
            expectedMinQuantity = maxQuantity + 1;
        }

        throw new CatalogValidationException("INVALID_TIER_STRUCTURE: final tier must have null maxQuantity.");
    }

    private void validateTierValueConstraints(CostTierDto tier, Integer minQuantity, Integer maxQuantity) {
        BigDecimal unitCost = tier.getUnitCost();
        if (minQuantity == null || minQuantity < 1) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: minQuantity must be >= 1.");
        }
        if (unitCost == null || unitCost.signum() <= 0) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: unitCost must be positive.");
        }
        if (maxQuantity != null && minQuantity > maxQuantity) {
            throw new CatalogValidationException(
                    "INVALID_TIER_STRUCTURE: minQuantity cannot be greater than maxQuantity.");
        }
    }

    private void validateTierSequenceConstraints(Integer minQuantity, Integer maxQuantity, int expectedMinQuantity,
            int index, int size) {
        if (minQuantity < expectedMinQuantity) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: tier ranges cannot overlap.");
        }
        if (minQuantity > expectedMinQuantity) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: tier ranges must be contiguous.");
        }
        if (maxQuantity == null && index < size - 1) {
            throw new CatalogValidationException("INVALID_TIER_STRUCTURE: only final tier can have null maxQuantity.");
        }
    }

    private List<CostTierEntity> toTierEntities(List<CostTierDto> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }

        List<CostTierDto> sortedTiers = new ArrayList<>(tiers);
        sortedTiers.sort(Comparator.comparing(CostTierDto::getMinQuantity));

        List<CostTierEntity> entities = new ArrayList<>(sortedTiers.size());
        for (CostTierDto tier : sortedTiers) {
            CostTierEntity entity = new CostTierEntity();
            entity.setMinQuantity(tier.getMinQuantity());
            entity.setMaxQuantity(tier.getMaxQuantity());
            entity.setUnitCost(tier.getUnitCost());
            entities.add(entity);
        }
        return entities;
    }

    private SupplierItemCostDto toDto(SupplierItemCostEntity entity) {
        SupplierItemCostDto dto = new SupplierItemCostDto();
        dto.setId(entity.getId());
        dto.setSupplierId(entity.getSupplierId());
        dto.setItemId(entity.getItemId());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setBaseCost(entity.getBaseCost());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setTiers(entity.getCostTiers().stream()
                .sorted(Comparator.comparing(CostTierEntity::getMinQuantity))
                .map(tier -> {
                    CostTierDto tierDto = new CostTierDto();
                    tierDto.setMinQuantity(tier.getMinQuantity());
                    tierDto.setMaxQuantity(tier.getMaxQuantity());
                    tierDto.setUnitCost(tier.getUnitCost());
                    return tierDto;
                })
                .toList());
        return dto;
    }

    private String normalizeCurrencyCode(String currencyCode) {
        return currencyCode.trim().toUpperCase(Locale.ROOT);
    }
}
