package com.positivity.mcp.internal.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * Fills {@code {placeholder}} tokens in a screen {@code url_template} from resolved request params.
 *
 * <p>Provided params are substituted (URL-encoded). Any query-string parameter whose placeholder is
 * still unresolved is dropped, so {@code /workorders?status={status}&location={locationId}} with only
 * {@code status=OPEN} becomes {@code /workorders?status=OPEN}. Unresolved placeholders in the path
 * portion are left as-is (there are none in the seeded templates).
 */
final class ScreenUrlTemplate {

    private ScreenUrlTemplate() {}

    static @NonNull String fill(@NonNull String template, @NonNull Map<String, String> params) {
        String result = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace("{" + entry.getKey() + "}", encode(entry.getValue()));
            }
        }

        int queryStart = result.indexOf('?');
        if (queryStart < 0) {
            return result;
        }
        String base = result.substring(0, queryStart);
        String kept = Arrays.stream(result.substring(queryStart + 1).split("&"))
                .filter(pair -> !pair.isBlank())
                .filter(pair -> !pair.contains("{")) // unresolved placeholder → drop the pair
                .collect(Collectors.joining("&"));
        return kept.isEmpty() ? base : base + "?" + kept;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
