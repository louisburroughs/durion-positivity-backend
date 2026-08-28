package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Putaway rules, keyed entirely by business names.
 *
 * <p>Resolving the catalog class is the awkward part. pos-catalog exposes no endpoint that lists
 * categories, so a name cannot be looked up directly. What it does expose is a product's resolved
 * category and subcategory, so a name is resolved through an <em>exemplar</em>: search for a
 * product carrying that class, read the class id back off it, and check the name that came back is
 * the one asked for. That last check matters — the search matches loosely, and a rule that resolved
 * to a neighbouring category would route a whole class of parts to the wrong bin while looking like
 * a clean load.
 */
@Component
@Slf4j
public class PutawayRuleLoaderStrategy implements DomainLoaderStrategy<PutawayRuleLoaderRecord> {

    private static final String CATALOG_SERVICE_ID = "catalog";
    private static final String ANY_TIER = "ANY";
    private static final String CATEGORY = "CATEGORY";

    @Override
    public DomainType getDomainType() {
        return DomainType.PUTAWAY_RULE;
    }

    @Override
    public PutawayRuleLoaderRecord mapRow(@NonNull Map<String, String> row) {
        PutawayRuleLoaderRecord record = new PutawayRuleLoaderRecord();
        record.setPriority(row.get("priority"));
        record.setMatchType(row.get("matchType"));
        record.setMatchName(row.get("matchName"));
        record.setLocationCode(row.get("locationCode"));
        record.setDestinationName(row.get("destinationName"));
        record.setDestinationStrategy(row.get("destinationStrategy"));
        record.setIsEnabled(row.get("isEnabled"));
        record.setMatchValue(row.get("matchValue"));
        record.setDestinationLocationId(row.get("destinationLocationId"));
        return record;
    }

    @Override
    @NonNull
    public PutawayRuleLoaderRecord resolve(@NonNull PutawayRuleLoaderRecord item, @NonNull ResolutionContext context) {

        if (LoaderValues.isBlank(item.getDestinationLocationId())
                && LoaderValues.isPresent(item.getLocationCode())
                && LoaderValues.isPresent(item.getDestinationName())) {
            LocationResolutions.storageLocationId(context, item.getLocationCode(), item.getDestinationName())
                    .ifPresent(item::setDestinationLocationId);
        }

        boolean needsMatchValue = LoaderValues.isPresent(item.getMatchType())
                && !ANY_TIER.equalsIgnoreCase(item.getMatchType().trim());
        if (needsMatchValue
                && LoaderValues.isBlank(item.getMatchValue())
                && LoaderValues.isPresent(item.getMatchName())) {
            resolveCatalogClass(
                            context,
                            item.getMatchType().trim(),
                            item.getMatchName().trim())
                    .ifPresent(item::setMatchValue);
        }
        return item;
    }

    /** The catalog id for a class name, found through a product that carries it. */
    private Optional<String> resolveCatalogClass(ResolutionContext context, String matchType, String matchName) {
        String cacheKey = "catalog-class:" + matchType + ':' + matchName.toLowerCase(Locale.ROOT);
        return context.memoize(cacheKey, () -> {
            String field = CATEGORY.equalsIgnoreCase(matchType) ? "category" : "subcategory";
            Optional<Map<String, Object>> exemplar = findExemplarProduct(context, field, matchName);
            if (exemplar.isEmpty()) {
                log.warn(
                        "Putaway rule {} '{}': no catalog product carries this class — the row will fail on its"
                                + " missing matchValue",
                        matchType,
                        matchName);
                return Optional.empty();
            }

            Map<String, Object> node = asMap(exemplar.get().get(field));
            String id = asText(node.get("id"));
            String resolvedName = asText(node.get("name"));
            if (id == null || resolvedName == null || !resolvedName.trim().equalsIgnoreCase(matchName)) {
                // The exemplar resolved to a different class than the row named, so the id is not
                // the one this rule means. Refusing beats routing a whole class to the wrong bin.
                log.warn(
                        "Putaway rule {} '{}': the exemplar product resolved to '{}' instead",
                        matchType,
                        matchName,
                        resolvedName);
                return Optional.empty();
            }
            return Optional.of(id);
        });
    }

    /**
     * A product carrying the named class, read back in full so its resolved class ids are visible.
     * The search returns summaries; only the detail view carries the category node.
     */
    private Optional<Map<String, Object>> findExemplarProduct(
            ResolutionContext context, String field, String matchName) {

        String searchUri = UriComponentsBuilder.fromPath("/v1/catalog/products/search")
                .queryParam(field, matchName)
                .queryParam("limit", 1)
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        List<Map<String, Object>> matches = context.get(CATALOG_SERVICE_ID, searchUri, Map.class)
                .map(body -> asMapList(body.get("data")))
                .orElseGet(List::of);
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        String productId = asText(matches.getFirst().get("productId"));
        if (productId == null) {
            return Optional.empty();
        }
        return context.get(CATALOG_SERVICE_ID, "/v1/catalog/products/" + productId, Map.class)
                .map(this::castToMap);
    }

    @Override
    public List<String> validate(@NonNull PutawayRuleLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getPriority())) {
            errors.add("priority is required");
        } else {
            LoaderValues.requireIntegerOrBlank(item.getPriority(), "priority", errors);
        }
        if (LoaderValues.isBlank(item.getMatchType())) {
            errors.add("matchType is required");
        }
        LoaderValues.requireUuid(
                item.getDestinationLocationId(),
                "destinationLocationId",
                "a locationCode and destinationName that resolve to one",
                errors);

        boolean isAnyTier = LoaderValues.isPresent(item.getMatchType())
                && ANY_TIER.equalsIgnoreCase(item.getMatchType().trim());
        if (isAnyTier) {
            // The terminal fallback matches everything, so a match value would contradict it — the
            // endpoint rejects one outright.
            if (LoaderValues.isPresent(item.getMatchValue())) {
                errors.add("matchValue must be omitted for the ANY tier");
            }
        } else {
            LoaderValues.requireUuid(
                    item.getMatchValue(), "matchValue", "a matchName that resolves to a catalog class", errors);
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Map<String, Object> asMap(Object value) {
        return castToMap(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
