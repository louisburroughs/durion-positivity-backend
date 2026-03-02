package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.client.InventoryClient;
import com.positivity.catalog.internal.client.PricingClient;
import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDetailView.*;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductReplacementEntity;
import com.positivity.catalog.internal.repository.ProductReplacementRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductDetailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private final ProductReplacementRepository productReplacementRepository;
    private final PricingClient pricingClient;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;

    @Value("${pos.price.default-customer-tier-id:00000000-0000-0000-0000-000000000001}")
    private UUID defaultCustomerTierId;

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
            log.debug("Fetching pricing for productId={}, locationId={}", productId, locationId);
            Optional<PricingClient.PriceQuoteClientResponse> quote = pricingClient.fetchPrice(productId, locationId,
                    defaultCustomerTierId);

            if (quote.isEmpty()) {
                log.warn("Price service returned no data for productId={}", productId);
                return PricingInfo.builder()
                        .status(DataStatus.UNAVAILABLE)
                        .asOf(requestTime)
                        .confidence(DataConfidence.LOW)
                        .build();
            }

            PricingClient.PriceQuoteClientResponse q = quote.get();
            return PricingInfo.builder()
                    .msrp(q.msrpAmount() != null ? q.msrpAmount().doubleValue() : null)
                    .storePrice(q.unitPriceAmount() != null ? q.unitPriceAmount().doubleValue() : null)
                    .currency(q.msrpCurrency())
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
            log.debug("Fetching availability for productId={}, locationId={}", productId, locationId);
            Optional<InventoryClient.AvailabilityClientResponse> avail = inventoryClient
                    .fetchAvailability(product.getSku(), locationId);

            if (avail.isEmpty()) {
                log.warn("Inventory service returned no data for productId={}", productId);
                LeadTimeInfo catalogLeadTime = getCatalogLeadTime(product, requestTime);
                return AvailabilityInfo.builder()
                        .leadTime(catalogLeadTime)
                        .status(DataStatus.UNAVAILABLE)
                        .asOf(requestTime)
                        .confidence(DataConfidence.LOW)
                        .build();
            }

            InventoryClient.AvailabilityClientResponse a = avail.get();
            LeadTimeInfo leadTime = getLeadTimeInfo(productId, locationId, requestTime, product);
            return AvailabilityInfo.builder()
                    .onHandQuantity(a.onHandQuantity())
                    .availableToPromiseQuantity(a.availableToPromiseQuantity())
                    .leadTime(leadTime)
                    .status(DataStatus.OK)
                    .asOf(requestTime)
                    .confidence(DataConfidence.MEDIUM)
                    .build();

        } catch (Exception e) {
            log.error("Failed to fetch availability for productId={}: {}", productId, e.getMessage());
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
            log.debug("Attempting to fetch dynamic lead time for productId={}", productId);
            Optional<InventoryClient.LeadTimeClientResponse> leadTimeOpt = inventoryClient.fetchLeadTime(productId,
                    locationId);
            if (leadTimeOpt.isEmpty()) {
                return getCatalogLeadTime(product, requestTime);
            }

            InventoryClient.LeadTimeClientResponse leadTime = leadTimeOpt.get();
            return LeadTimeInfo.builder()
                    .source(parseLeadTimeSource(leadTime.source()))
                    .minDays(leadTime.minDays())
                    .maxDays(leadTime.maxDays())
                    .displayText(leadTime.displayText())
                    .asOf(leadTime.asOf() != null ? leadTime.asOf() : requestTime)
                    .confidence(parseDataConfidence(leadTime.confidence()))
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
        CatalogLeadTimeHint hint = extractCatalogLeadTimeHint(product);
        LeadTimeRange range = normalizeCatalogLeadTimeRange(hint.minDays(), hint.maxDays());
        String displayText = resolveCatalogLeadTimeDisplayText(hint.displayText(), range.minDays(), range.maxDays());

        return LeadTimeInfo.builder()
                .source(LeadTimeSource.CATALOG)
                .minDays(range.minDays())
                .maxDays(range.maxDays())
                .displayText(displayText)
                .asOf(requestTime)
                .confidence(hint.hasHint() ? DataConfidence.MEDIUM : DataConfidence.LOW)
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
        log.debug("Fetching substitutions for productId={}", productId);
        if (productId == null) {
            return List.of();
        }

        Instant now = Instant.now();
        try {
            return productReplacementRepository
                    .findByOriginalProductIdAndDeletedAtIsNullOrderByPriorityOrderAsc(productId)
                    .stream()
                    .filter(replacement -> isEffectiveReplacement(replacement, now))
                    .map(this::toSubstitutionHint)
                    .toList();
        } catch (Exception ex) {
            log.warn("Failed to fetch substitutions for productId={}: {}", productId, ex.getMessage());
            return List.of();
        }
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

    private LeadTimeSource parseLeadTimeSource(String source) {
        if (source == null || source.isBlank()) {
            return LeadTimeSource.INVENTORY;
        }
        try {
            return LeadTimeSource.valueOf(source);
        } catch (IllegalArgumentException ex) {
            return LeadTimeSource.INVENTORY;
        }
    }

    private DataConfidence parseDataConfidence(String confidence) {
        if (confidence == null || confidence.isBlank()) {
            return DataConfidence.MEDIUM;
        }
        try {
            return DataConfidence.valueOf(confidence);
        } catch (IllegalArgumentException ex) {
            return DataConfidence.MEDIUM;
        }
    }

    private Integer readInteger(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isInt()) {
                return value.asInt();
            }
            if (value.isString()) {
                String raw = value.asString();
                if (raw != null && !raw.isBlank()) {
                    try {
                        return Integer.parseInt(raw.trim());
                    } catch (NumberFormatException ignored) {
                        // Keep trying other keys.
                    }
                }
            }
        }
        return null;
    }

    private String readString(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asString(null);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private boolean isEffectiveReplacement(ProductReplacementEntity replacement, Instant now) {
        return replacement.getEffectiveAt() == null || !replacement.getEffectiveAt().isAfter(now);
    }

    private SubstitutionHint toSubstitutionHint(ProductReplacementEntity replacement) {
        String reason = replacement.getNotes();
        if (reason == null || reason.isBlank()) {
            reason = "Replacement option";
        }
        return SubstitutionHint.builder()
                .productId(replacement.getReplacementProductId() != null
                        ? replacement.getReplacementProductId().toString()
                        : null)
                .reason(reason)
                .build();
    }

    private CatalogLeadTimeHint extractCatalogLeadTimeHint(ProductEntity product) {
        if (product == null || product.getAttributes() == null || product.getAttributes().isBlank()) {
            return CatalogLeadTimeHint.empty();
        }
        try {
            JsonNode attributes = objectMapper.readTree(product.getAttributes());
            Integer exactDays = readInteger(
                    attributes,
                    "leadTimeDays",
                    "lead_time_days",
                    "leadTimeHintDays",
                    "lead_time_hint_days");
            Integer minDays = readInteger(
                    attributes,
                    "leadTimeMinDays",
                    "lead_time_min_days",
                    "leadTimeDaysMin",
                    "lead_time_days_min");
            Integer maxDays = readInteger(
                    attributes,
                    "leadTimeMaxDays",
                    "lead_time_max_days",
                    "leadTimeDaysMax",
                    "lead_time_days_max");
            if (exactDays != null) {
                minDays = minDays == null ? exactDays : minDays;
                maxDays = maxDays == null ? exactDays : maxDays;
            }

            String displayText = readString(
                    attributes,
                    "leadTimeDisplayText",
                    "lead_time_display_text",
                    "leadTimeText",
                    "lead_time_text");
            boolean hasHint = minDays != null || maxDays != null || displayText != null;
            return new CatalogLeadTimeHint(minDays, maxDays, displayText, hasHint);
        } catch (Exception ex) {
            log.debug("Unable to parse product attributes for catalog lead time, using defaults: {}", ex.getMessage());
            return CatalogLeadTimeHint.empty();
        }
    }

    private LeadTimeRange normalizeCatalogLeadTimeRange(Integer minDays, Integer maxDays) {
        int resolvedMin = minDays == null ? (maxDays == null ? 3 : maxDays) : minDays;
        int resolvedMax = maxDays == null ? resolvedMin : maxDays;
        if (resolvedMin <= resolvedMax) {
            return new LeadTimeRange(resolvedMin, resolvedMax);
        }
        return new LeadTimeRange(resolvedMax, resolvedMin);
    }

    private String resolveCatalogLeadTimeDisplayText(String displayText, int minDays, int maxDays) {
        if (displayText != null && !displayText.isBlank()) {
            return displayText;
        }
        if (minDays == maxDays) {
            return minDays + " business days (estimated)";
        }
        return minDays + "-" + maxDays + " business days (estimated)";
    }

    private record CatalogLeadTimeHint(Integer minDays, Integer maxDays, String displayText, boolean hasHint) {
        private static CatalogLeadTimeHint empty() {
            return new CatalogLeadTimeHint(null, null, null, false);
        }
    }

    private record LeadTimeRange(int minDays, int maxDays) {
    }
}
