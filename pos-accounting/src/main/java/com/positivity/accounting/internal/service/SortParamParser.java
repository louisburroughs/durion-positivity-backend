package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.exception.UnsupportedSortPropertyException;
import java.util.Map;
import org.springframework.data.domain.Sort;

/**
 * Parses the raw {@code sort} request parameter used by the module's list
 * endpoints into a validated {@link Sort}.
 *
 * Accepts the Spring convention {@code property[,direction]} (e.g.
 * {@code modifiedAt,desc}) and maps API field names to entity properties via a
 * per-endpoint whitelist, so an unknown field or direction yields a 400
 * ({@link UnsupportedSortPropertyException}, handled by
 * {@code APPaymentExceptionHandler}) instead of an unhandled
 * {@code PropertyReferenceException} 500.
 */
public final class SortParamParser {

    private SortParamParser() {
        // Utility class
    }

    /**
     * @param sort               raw request value, {@code property[,direction]}
     * @param sortableProperties API field name → entity property whitelist
     * @param defaultDirection   direction applied when the value has none
     * @throws UnsupportedSortPropertyException for unknown properties or
     *                                          directions (mapped to 400)
     */
    public static Sort parse(String sort, Map<String, String> sortableProperties, Sort.Direction defaultDirection) {
        String[] parts = sort.split(",", 2);
        String requested = parts[0].trim();
        String property = sortableProperties.get(requested);
        if (property == null) {
            throw new UnsupportedSortPropertyException("Unsupported sort property: '" + requested + "'. Supported: "
                    + sortableProperties.keySet().stream().sorted().toList());
        }
        Sort.Direction direction = defaultDirection;
        if (parts.length == 2 && !parts[1].isBlank()) {
            String requestedDirection = parts[1].trim();
            direction = Sort.Direction.fromOptionalString(requestedDirection)
                    .orElseThrow(() -> new UnsupportedSortPropertyException(
                            "Unsupported sort direction: '" + requestedDirection + "'. Use 'asc' or 'desc'."));
        }
        return Sort.by(direction, property);
    }
}
