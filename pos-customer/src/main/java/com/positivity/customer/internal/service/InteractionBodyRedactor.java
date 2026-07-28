package com.positivity.customer.internal.service;

import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Masks contact details and long digit runs in free-text interaction bodies before they
 * leave the service (Story #1141, DECISION-INVENTORY-006).
 *
 * <p>Interaction bodies are typed by humans, so they accumulate the things a CRM timeline
 * should not hand back verbatim: a customer's email dictated over the phone, a card number
 * jotted into a call note. Redaction happens on read rather than on write so the original
 * text stays available to a compliance export that is entitled to it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InteractionBodyRedactor {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /**
     * Seven or more digits, allowing separators. Shorter runs are left alone because they
     * are far more often a mileage, a part number, or a price than a phone or card number.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("(?<!\\d)(?:\\d[ .-]?){7,}\\d(?!\\d)");

    public static @Nullable String redact(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String masked = EMAIL.matcher(body).replaceAll("[redacted-email]");
        return LONG_DIGIT_RUN.matcher(masked).replaceAll("[redacted-number]");
    }

    /** Whether redaction would change the text — used to flag partially masked bodies in UIs. */
    public static boolean containsRedactableContent(@NonNull String body) {
        return EMAIL.matcher(body).find() || LONG_DIGIT_RUN.matcher(body).find();
    }
}
