package com.positivity.bulkloader.internal.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.util.UriComponentsBuilder;

/** Catalog lookups, for the packs whose files name products by SKU rather than by id. */
@Slf4j
public final class CatalogResolutions {

    private static final String CATALOG_SERVICE_ID = "catalog";

    private CatalogResolutions() {}

    /** The product id behind a SKU, or empty when the catalog has no product with it. */
    @NonNull
    public static Optional<String> productId(@NonNull ResolutionContext context, @NonNull String sku) {
        String trimmed = sku.trim();
        return context.memoize("product:" + trimmed.toLowerCase(Locale.ROOT), () -> {
            String uri = UriComponentsBuilder.fromPath("/v1/catalog/products/search")
                    .queryParam("sku", trimmed)
                    .queryParam("limit", 1)
                    .encode(StandardCharsets.UTF_8)
                    .toUriString();

            Optional<String> productId = context.get(CATALOG_SERVICE_ID, uri, Map.class)
                    .map(body -> body.get("data"))
                    .map(CatalogResolutions::asMapList)
                    .filter(matches -> !matches.isEmpty())
                    .map(matches -> matches.getFirst().get("productId"))
                    .map(Object::toString)
                    .filter(value -> !value.isBlank());
            if (productId.isEmpty()) {
                log.warn("SKU '{}' is not in the catalog — load the catalog pack first", trimmed);
            }
            return productId;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
