package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.service.ContentDetectionService;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RuleBasedContentDetectionServiceImpl implements ContentDetectionService {

    private static final String PRICE_FIELD = "price";
    private static final String QUANTITY_FIELD = "quantity";

    private static final Map<DomainType, Set<String>> DOMAIN_HEADER_SYNONYMS = Map.of(
            DomainType.CATALOG_PRODUCT,
            Set.of(
                    "sku",
                    "upc",
                    "product",
                    "description",
                    "category",
                    PRICE_FIELD,
                    "name",
                    "manufacturer",
                    "part_number",
                    "weight"),
            DomainType.INVENTORY_STOCK_COUNT,
            Set.of("sku", QUANTITY_FIELD, "qty", "stock", "on_hand", "location_code", "bin", "unit_cost", "warehouse"),
            DomainType.LOCATION,
            Set.of(
                    "location",
                    "store",
                    "branch",
                    "address",
                    "city",
                    "state",
                    "zip",
                    "timezone",
                    "code",
                    "operating_hours"));

    @Override
    public ContentDetectionResult detect(@NonNull List<String> columnHeaders, @NonNull List<List<String>> sampleRows) {
        Map<DomainType, Integer> scores = new EnumMap<>(DomainType.class);
        for (DomainType domain : DomainType.values()) {
            Set<String> synonyms = DOMAIN_HEADER_SYNONYMS.get(domain);
            if (synonyms == null) {
                continue;
            }
            int score = 0;
            for (String header : columnHeaders) {
                String normalized = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
                if (normalized.isEmpty()) {
                    continue;
                }
                if (synonyms.stream().anyMatch(s -> normalized.contains(s) || s.contains(normalized))) {
                    score++;
                }
            }
            scores.put(domain, score);
        }

        DomainType best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(DomainType.CATALOG_PRODUCT);
        int maxScore = scores.getOrDefault(best, 0);
        double confidence = columnHeaders.isEmpty() ? 0.0 : (double) maxScore / columnHeaders.size();
        Map<String, String> suggestedMappings = buildSuggestedMappings(columnHeaders, best);

        log.debug("Content detection: domain={} confidence={} headers={}", best, confidence, columnHeaders);
        return ContentDetectionResult.builder()
                .detectedDomain(best)
                .confidence(confidence)
                .suggestedColumnMappings(suggestedMappings)
                .llmFallbackUsed(false)
                .build();
    }

    private Map<String, String> buildSuggestedMappings(List<String> headers, DomainType domain) {
        Map<String, String> mappings = new HashMap<>();
        for (String header : headers) {
            String normalized = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
            String target = inferTargetField(normalized, domain);
            if (target != null) {
                mappings.put(header, target);
            }
        }
        return mappings;
    }

    private String inferTargetField(String normalized, DomainType domain) {
        return switch (domain) {
            case CATALOG_PRODUCT ->
                switch (normalized) {
                    case "sku", "item_number", "item_no", "part_no" -> "sku";
                    case "upc", "barcode", "ean" -> "upc";
                    case "name", "item_name", "product_name", "description" -> "name";
                    case PRICE_FIELD, "list_price", "retail_price" -> PRICE_FIELD;
                    case "category", "category_name" -> "categoryName";
                    case "subcategory", "subcategory_name" -> "subcategoryName";
                    default -> null;
                };
            case INVENTORY_STOCK_COUNT ->
                switch (normalized) {
                    case "sku", "item_number", "part_no" -> "sku";
                    case "qty", QUANTITY_FIELD, "on_hand", "quantity_on_hand" -> QUANTITY_FIELD;
                    case "reason_code", "reason" -> "reasonCode";
                    case "uom", "unit_of_measure" -> "unitOfMeasure";
                    default -> null;
                };
            case LOCATION ->
                switch (normalized) {
                    case "name", "store_name", "location_name" -> "name";
                    case "code", "store_code", "location_code" -> "code";
                    case "address", "street", "street_address" -> "addressLine1";
                    case "city" -> "city";
                    case "state", "state_or_province", "province" -> "stateOrProvince";
                    case "zip", "zip_code", "postal_code" -> "postalCode";
                    case "country", "country_code" -> "countryCode";
                    case "phone", "phone_number", "telephone" -> "phoneNumber";
                    case "type", "store_type", "location_type" -> "locationTypeName";
                    default -> null;
                };
            default -> null;
        };
    }
}
