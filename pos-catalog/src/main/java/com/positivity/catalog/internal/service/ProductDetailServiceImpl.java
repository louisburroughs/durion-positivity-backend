package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDetailView.*;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductDetailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for aggregating product details with pricing and availability
 * information.
 * Implements graceful degradation per clarification resolution for Issue #16.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDetailServiceImpl implements ProductDetailService {

    private final ProductRepository productRepository;
    // TODO: Inject PricingService when available
    // TODO: Inject InventoryService when available

    /**
     * Retrieves a consolidated product detail view with pricing and availability.
     * Implements graceful degradation: returns partial data if non-critical
     * services are unavailable.
     * 
     * @param productId  The unique product identifier
     * @param locationId The location/store identifier for location-specific data
     * @return ProductDetailView with status indicators for each data component
     */
    @Override
    @Cacheable(value = "productDetails", key = "#productId + '-' + #locationId")
    public ProductDetailView getProductDetail(UUID productId, UUID locationId) {
        log.info("Fetching product detail for productId={}, locationId={}", productId, locationId);

        Instant requestTime = Instant.now();

        // Fetch product master data from catalog (this is required - fail if not found)
        Optional<ProductEntity> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            log.warn("Product not found: productId={}", productId);
            return null; // Will result in 404 at controller level
        }

        ProductEntity product = productOpt.get();

        // Build specifications from product entity
        List<ProductSpecification> specifications = buildSpecifications(product);

        // Fetch pricing (with graceful degradation)
        PricingInfo pricing = fetchPricingInfo(productId, locationId, requestTime);

        // Fetch availability (with graceful degradation)
        AvailabilityInfo availability = fetchAvailabilityInfo(productId, locationId, requestTime, product);

        // Fetch substitutions (from catalog)
        List<SubstitutionHint> substitutions = fetchSubstitutions(productId);

        // Calculate overall confidence
        DataConfidence overallConfidence = calculateOverallConfidence(pricing, availability);

        return ProductDetailView.builder()
                .productId(String.valueOf(productId))
                .description(product.getLongDescription())
                .specifications(specifications)
                .pricing(pricing)
                .availability(availability)
                .substitutions(substitutions)
                .generatedAt(requestTime)
                .confidence(overallConfidence)
                .build();
    }

    /**
     * Fetches pricing information with graceful degradation.
     * Returns status=UNAVAILABLE if pricing service is down.
     */
    private PricingInfo fetchPricingInfo(UUID productId, UUID locationId, Instant requestTime) {
        try {
            // TODO: Replace with actual call to Pricing Service
            // For now, return mock data with OK status
            log.debug("Fetching pricing for productId={}, locationId={}", productId, locationId);

            // Mock implementation - replace with actual service call
            return PricingInfo.builder()
                    .msrp(99.99)
                    .storePrice(89.99)
                    .currency("USD")
                    .status(DataStatus.OK)
                    .asOf(requestTime)
                    .confidence(DataConfidence.HIGH)
                    .build();

        } catch (Exception e) {
            log.error("Failed to fetch pricing for productId={}: {}", productId, e.getMessage());
            return PricingInfo.builder()
                    .status(DataStatus.UNAVAILABLE)
                    .asOf(requestTime)
                    .confidence(DataConfidence.LOW)
                    .build();
        }
    }

    /**
     * Fetches availability information with graceful degradation.
     * Returns status=UNAVAILABLE if inventory service is down.
     * Implements two-tier lead time model: catalog static + dynamic override.
     */
    private AvailabilityInfo fetchAvailabilityInfo(UUID productId, UUID locationId, Instant requestTime,
            ProductEntity product) {
        try {
            // TODO: Replace with actual call to Inventory Service
            log.debug("Fetching availability for productId={}, locationId={}", productId, locationId);

            // Get static lead time from catalog
            LeadTimeInfo leadTime = getLeadTimeInfo(productId, locationId, requestTime, product);

            // Mock implementation - replace with actual service call
            return AvailabilityInfo.builder()
                    .onHandQuantity(15)
                    .availableToPromiseQuantity(12)
                    .leadTime(leadTime)
                    .status(DataStatus.OK)
                    .asOf(requestTime)
                    .confidence(DataConfidence.MEDIUM)
                    .build();

        } catch (Exception e) {
            log.error("Failed to fetch availability for productId={}: {}", productId, e.getMessage());

            // Return with UNKNOWN status but still include catalog lead time
            LeadTimeInfo catalogLeadTime = getCatalogLeadTime(product, requestTime);

            return AvailabilityInfo.builder()
                    .leadTime(catalogLeadTime)
                    .status(DataStatus.UNAVAILABLE)
                    .asOf(requestTime)
                    .confidence(DataConfidence.LOW)
                    .build();
        }
    }

    /**
     * Gets lead time information using two-tier model:
     * 1. Catalog static hint (always available)
     * 2. Dynamic override from Inventory/Supply Chain (best effort)
     */
    private LeadTimeInfo getLeadTimeInfo(UUID productId, UUID locationId, Instant requestTime, ProductEntity product) {
        try {
            // TODO: Try to fetch dynamic lead time from Inventory/Supply Chain service
            log.debug("Attempting to fetch dynamic lead time for productId={}", productId);

            // Mock: Return dynamic lead time with HIGH confidence
            return LeadTimeInfo.builder()
                    .source(LeadTimeSource.INVENTORY)
                    .minDays(2)
                    .maxDays(5)
                    .displayText("2-5 business days")
                    .asOf(requestTime)
                    .confidence(DataConfidence.HIGH)
                    .build();

        } catch (Exception e) {
            log.debug("Dynamic lead time unavailable, falling back to catalog hint: {}", e.getMessage());
            return getCatalogLeadTime(product, requestTime);
        }
    }

    /**
     * Gets static lead time hint from catalog.
     * This is the fallback when dynamic lead time is unavailable.
     */
    private LeadTimeInfo getCatalogLeadTime(ProductEntity product, Instant requestTime) {
        // TODO: Add leadTimeHintDays field to ProductEntity
        // For now, return generic catalog hint
        return LeadTimeInfo.builder()
                .source(LeadTimeSource.CATALOG)
                .minDays(3)
                .maxDays(7)
                .displayText("3-7 business days (estimated)")
                .asOf(requestTime)
                .confidence(DataConfidence.LOW)
                .build();
    }

    /**
     * Builds specification list from product entity.
     */
    private List<ProductSpecification> buildSpecifications(ProductEntity product) {
        List<ProductSpecification> specs = new ArrayList<>();

        if (product.getMaterial() != null) {
            specs.add(ProductSpecification.builder()
                    .name("Material")
                    .value(product.getMaterial())
                    .build());
        }

        if (product.getColor() != null) {
            specs.add(ProductSpecification.builder()
                    .name("Color")
                    .value(product.getColor())
                    .build());
        }

        if (product.getWarranty() != null) {
            specs.add(ProductSpecification.builder()
                    .name("Warranty")
                    .value(product.getWarranty())
                    .build());
        }

        if (product.getManufacturerName() != null) {
            specs.add(ProductSpecification.builder()
                    .name("Manufacturer")
                    .value(product.getManufacturerName())
                    .build());
        }

        return specs;
    }

    /**
     * Fetches substitution hints from catalog.
     */
    private List<SubstitutionHint> fetchSubstitutions(UUID productId) {
        // TODO: Implement when substitution relationship is added to data model
        log.debug("Fetching substitutions for productId={}", productId);
        return new ArrayList<>();
    }

    /**
     * Calculates overall confidence based on component statuses.
     */
    private DataConfidence calculateOverallConfidence(PricingInfo pricing, AvailabilityInfo availability) {
        boolean pricingAvailable = pricing.getStatus() == DataStatus.OK;
        boolean availabilityAvailable = availability.getStatus() == DataStatus.OK;

        if (pricingAvailable && availabilityAvailable) {
            return DataConfidence.HIGH;
        } else if (pricingAvailable || availabilityAvailable) {
            return DataConfidence.MEDIUM;
        } else {
            return DataConfidence.LOW;
        }
    }
}
