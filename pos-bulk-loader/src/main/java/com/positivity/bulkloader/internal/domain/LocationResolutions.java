package com.positivity.bulkloader.internal.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Site and storage-location lookups, shared by every strategy whose file names places rather than
 * carrying their ids.
 *
 * <p>Held in one place because these two lookups are the same wherever they appear, and because the
 * caching shape matters: a site's whole storage list is fetched once and reused, so a 263-row file
 * costs one call rather than one per row. Repeating that per strategy invites one of them to get it
 * wrong quietly.
 */
@Slf4j
public final class LocationResolutions {

    private static final String LOCATION_SERVICE_ID = "location";

    /** One page big enough for a site's whole topology; the alpha sites carry 38 rows each. */
    private static final int STORAGE_PAGE_SIZE = 500;

    private LocationResolutions() {}

    /** The site id for a location code, or empty when the roster has no such code. */
    @NonNull
    public static Optional<String> siteId(@NonNull ResolutionContext context, @NonNull String locationCode) {
        String code = locationCode.trim();
        return context.memoize("site:" + code.toLowerCase(Locale.ROOT), () -> {
            List<Map<String, Object>> roster = context.get(LOCATION_SERVICE_ID, "/v1/locations", List.class)
                    .map(LocationResolutions::asMapList)
                    .orElseGet(List::of);
            Optional<String> match = roster.stream()
                    .filter(location -> code.equalsIgnoreCase(asText(location.get("code"))))
                    .map(location -> asText(location.get("id")))
                    .filter(LocationResolutions::isPresent)
                    .findFirst();
            if (match.isEmpty()) {
                log.warn("Site '{}' is not in the location roster", code);
            }
            return match;
        });
    }

    /**
     * The id of a named storage location at a site, or empty when the site has no location by that
     * name. Never falls back to the site itself: stock or a rule pointing at the site instead of the
     * bin it named is somewhere real but wrong, which loads cleanly and surfaces much later.
     */
    @NonNull
    public static Optional<String> storageLocationId(
            @NonNull ResolutionContext context,
            @NonNull String siteId,
            @NonNull String locationCode,
            @NonNull String storageName) {

        String name = storageName.trim();
        return context.memoize("storage:" + siteId + ':' + name.toLowerCase(Locale.ROOT), () -> {
            Optional<String> match = storageLocationsOf(context, siteId).stream()
                    .filter(storage -> name.equalsIgnoreCase(asText(storage.get("name"))))
                    .map(storage -> asText(storage.get("id")))
                    .filter(LocationResolutions::isPresent)
                    .findFirst();
            if (match.isEmpty()) {
                log.warn("Storage location '{}' does not exist at {}", name, locationCode);
            }
            return match;
        });
    }

    /** A site code and storage name resolved together, which is how files name a destination. */
    @NonNull
    public static Optional<String> storageLocationId(
            @NonNull ResolutionContext context, @NonNull String locationCode, @NonNull String storageName) {
        return siteId(context, locationCode)
                .flatMap(siteId -> storageLocationId(context, siteId, locationCode, storageName));
    }

    /** The site's storage list, fetched once per site however many rows name it. */
    private static List<Map<String, Object>> storageLocationsOf(ResolutionContext context, String siteId) {
        return context.<List<Map<String, Object>>>memoize("storage-page:" + siteId, () -> {
                    String uri = UriComponentsBuilder.fromPath("/v1/locations/{siteId}/storage-locations")
                            .queryParam("size", STORAGE_PAGE_SIZE)
                            .encode(StandardCharsets.UTF_8)
                            .buildAndExpand(siteId)
                            .toUriString();
                    return Optional.of(context.get(LOCATION_SERVICE_ID, uri, Map.class)
                            .map(body -> asMapList(body.get("content")))
                            .orElseGet(List::of));
                })
                .orElseGet(List::of);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
