package com.positivity.vehiclefitment.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.vehiclefitment.internal.dto.*;
import com.positivity.vehiclefitment.internal.entity.FitmentTag;
import com.positivity.vehiclefitment.internal.entity.TagType;
import com.positivity.vehiclefitment.internal.entity.VehicleApplicabilityHint;
import com.positivity.vehiclefitment.internal.repository.VehicleApplicabilityHintRepository;
import com.positivity.vehiclefitment.service.VehicleApplicabilityHintService;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing vehicle applicability hints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleApplicabilityHintServiceImpl implements VehicleApplicabilityHintService {

    private final VehicleApplicabilityHintRepository hintRepository;

    /**
     * Create a new vehicle applicability hint for a product.
     */
    @Override
    @Transactional
    public HintResponse createHint(CreateHintRequest request) {
        log.info("Creating vehicle applicability hint for product {}", request.getProductId());

        VehicleApplicabilityHint hint = new VehicleApplicabilityHint();
        hint.setProductId(request.getProductId());
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        hint.setCreatedBy(actor);
        hint.setUpdatedBy(actor);

        for (FitmentTagDto tagDto : request.getFitmentTags()) {
            FitmentTag tag = new FitmentTag();
            tag.setTagType(tagDto.getTagType());
            tag.setTagValue(tagDto.getTagValue());
            hint.addFitmentTag(tag);
        }

        VehicleApplicabilityHint savedHint = hintRepository.save(hint);
        log.info("Created hint with ID {} for product {}", savedHint.getHintId(), savedHint.getProductId());

        return mapToResponse(savedHint);
    }

    /**
     * Update an existing vehicle applicability hint.
     */
    @Override
    @Transactional
    public HintResponse updateHint(UUID hintId, UpdateHintRequest request) {
        log.info("Updating vehicle applicability hint {}", hintId);

        VehicleApplicabilityHint hint = hintRepository
                .findById(hintId)
                .orElseThrow(() -> new IllegalArgumentException("Hint not found with ID: " + hintId));

        hint.getFitmentTags().clear();

        for (FitmentTagDto tagDto : request.getFitmentTags()) {
            FitmentTag tag = new FitmentTag();
            tag.setTagType(tagDto.getTagType());
            tag.setTagValue(tagDto.getTagValue());
            hint.addFitmentTag(tag);
        }

        hint.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));

        VehicleApplicabilityHint updatedHint = hintRepository.save(hint);
        log.info("Updated hint {}", hintId);

        return mapToResponse(updatedHint);
    }

    /**
     * Delete a vehicle applicability hint.
     */
    @Override
    @Transactional
    public void deleteHint(UUID hintId) {
        log.info("Deleting vehicle applicability hint {}", hintId);

        if (!hintRepository.existsById(hintId)) {
            throw new IllegalArgumentException("Hint not found with ID: " + hintId);
        }

        hintRepository.deleteById(hintId);
        log.info("Deleted hint {}", hintId);
    }

    /**
     * Get a vehicle applicability hint by ID.
     */
    @Override
    @Transactional(readOnly = true)
    public HintResponse getHint(UUID hintId) {
        log.info("Retrieving vehicle applicability hint {}", hintId);

        VehicleApplicabilityHint hint = hintRepository
                .findById(hintId)
                .orElseThrow(() -> new IllegalArgumentException("Hint not found with ID: " + hintId));

        return mapToResponse(hint);
    }

    /**
     * Get all hints for a specific product.
     */
    @Override
    @Transactional(readOnly = true)
    public List<HintResponse> getHintsByProductId(UUID productId) {
        log.info("Retrieving hints for product {}", productId);

        List<VehicleApplicabilityHint> hints = hintRepository.findByProductId(productId);

        return hints.stream().map(this::mapToResponse).toList();
    }

    /**
     * Filter products by vehicle attributes.
     * A product matches if its hints contain tags that are compatible with all
     * provided attributes.
     */
    @Override
    @Transactional(readOnly = true)
    public FilterProductsResponse filterProductsByVehicleAttributes(Map<String, String> vehicleAttributes) {
        log.info("Filtering products by vehicle attributes: {}", vehicleAttributes);

        if (vehicleAttributes == null || vehicleAttributes.isEmpty()) {
            return new FilterProductsResponse(Collections.emptyList(), 0);
        }

        // Get all hints
        List<VehicleApplicabilityHint> allHints = hintRepository.findAll();

        // Filter hints that match all provided attributes
        Set<UUID> matchingProductIds = new HashSet<>();

        for (VehicleApplicabilityHint hint : allHints) {
            if (hintMatchesAttributes(hint, vehicleAttributes)) {
                matchingProductIds.add(hint.getProductId());
            }
        }

        List<UUID> productIdList = new ArrayList<>(matchingProductIds);
        log.info("Found {} matching products", productIdList.size());

        // Convert UUIDs to String for response
        List<String> productIdStrings =
                productIdList.stream().map(String::valueOf).toList();
        return new FilterProductsResponse(productIdStrings, productIdStrings.size());
    }

    /**
     * Check if a hint matches all provided vehicle attributes.
     */
    private boolean hintMatchesAttributes(VehicleApplicabilityHint hint, Map<String, String> attributes) {
        Map<TagType, String> hintTags = hint.getFitmentTags().stream()
                .collect(Collectors.toMap(FitmentTag::getTagType, FitmentTag::getTagValue, (v1, v2) -> v1));

        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            try {
                TagType tagType = TagType.valueOf(attribute.getKey().toUpperCase());
                String hintValue = hintTags.get(tagType);

                if (hintValue == null) {
                    continue;
                }

                if (!matchesTagValue(tagType, hintValue, attribute.getValue())) {
                    return false;
                }
            } catch (IllegalArgumentException e) {
                log.warn("Unknown tag type: {}", attribute.getKey());
            }
        }

        return true;
    }

    /**
     * Check if a tag value matches the provided attribute value.
     * Handles special cases like year ranges.
     */
    private boolean matchesTagValue(TagType tagType, String hintValue, String attributeValue) {
        if (tagType == TagType.YEAR_RANGE) {
            return matchesYearRange(hintValue, attributeValue);
        }

        return hintValue.equalsIgnoreCase(attributeValue);
    }

    /**
     * Check if a year value falls within a year range.
     * Range format: "2018-2022" or single year "2020"
     */
    private boolean matchesYearRange(String rangeValue, String yearValue) {
        try {
            int year = Integer.parseInt(yearValue);

            if (rangeValue.contains("-")) {
                String[] parts = rangeValue.split("-");
                int startYear = Integer.parseInt(parts[0].trim());
                int endYear = Integer.parseInt(parts[1].trim());
                return year >= startYear && year <= endYear;
            } else {
                int singleYear = Integer.parseInt(rangeValue.trim());
                return year == singleYear;
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid year format: range={}, value={}", rangeValue, yearValue);
            return false;
        }
    }

    /**
     * Map entity to response DTO.
     */
    private HintResponse mapToResponse(VehicleApplicabilityHint hint) {
        HintResponse response = new HintResponse();
        response.setHintId(hint.getHintId().toString());
        response.setProductId(String.valueOf(hint.getProductId()));
        response.setCreatedAt(hint.getCreatedAt());
        response.setUpdatedAt(hint.getUpdatedAt());
        response.setCreatedBy(hint.getCreatedBy());
        response.setUpdatedBy(hint.getUpdatedBy());

        List<FitmentTagDto> tagDtos = hint.getFitmentTags().stream()
                .map(tag -> new FitmentTagDto(tag.getTagType(), tag.getTagValue()))
                .toList();

        response.setFitmentTags(tagDtos);

        return response;
    }
}
