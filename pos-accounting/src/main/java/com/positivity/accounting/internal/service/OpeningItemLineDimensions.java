package com.positivity.accounting.internal.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The dimensions a bank opening balance writes on each outstanding-item line (#2572, OI-10): the item's type,
 * reference and own date. The entry is dated the cutover date, so bank reconciliation reads the item's own date
 * from here when the line is registered as an outstanding item.
 */
public final class OpeningItemLineDimensions {

    /** {@code OUTSTANDING_CHECK} or {@code DEPOSIT_IN_TRANSIT}. */
    public static final String TYPE = "outstandingItemType";

    /** The check number or deposit reference. */
    public static final String REFERENCE = "reference";

    /** The date the check was written or the deposit made (ISO-8601). */
    public static final String ITEM_DATE = "itemDate";

    private OpeningItemLineDimensions() {}

    /** The item date an opening line carries, if it is one and its date reads as an ISO-8601 date. */
    public static @NonNull Optional<LocalDate> itemDate(@Nullable Map<String, String> dimensions) {
        if (dimensions == null || dimensions.get(TYPE) == null) {
            return Optional.empty();
        }
        String value = dimensions.get(ITEM_DATE);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
