package com.positivity.location.internal.service;

import com.positivity.location.internal.enums.OutOfServiceReason;
import com.positivity.location.internal.exception.InvalidFieldException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The one lifecycle bays and mobile units share (DECISION-LOCATION-026, issue #2264): status
 * {@code ACTIVE} | {@code OUT_OF_SERVICE} | {@code RETIRED}, and the rules around going out of
 * service. Not a Spring bean, the same reason {@link ServiceCapabilityCodeValidator} is not one:
 * both services already own their entity's fields, and a plain collaborator keeps their
 * constructors as they are.
 */
public final class LifecycleStatusSupport {

    public static final String ACTIVE = "ACTIVE";
    public static final String OUT_OF_SERVICE = "OUT_OF_SERVICE";
    public static final String RETIRED = "RETIRED";

    public static final String OUT_OF_SERVICE_REASON_REQUIRED = "OUT_OF_SERVICE_REASON_REQUIRED";
    public static final String FIELD_OUT_OF_SERVICE_REASON = "outOfServiceReason";
    public static final String FIELD_OUT_OF_SERVICE_NOTE = "outOfServiceNote";

    private static final Set<String> STATUSES = Set.of(ACTIVE, OUT_OF_SERVICE, RETIRED);
    private static final Set<String> REASONS =
            java.util.Arrays.stream(OutOfServiceReason.values()).map(Enum::name).collect(Collectors.toSet());
    private static final int MAX_NOTE_LENGTH = 255;

    private LifecycleStatusSupport() {}

    /** Upper-cased/trimmed status, defaulting to {@code defaultValue} when blank; else 400. */
    public static String normalizeStatus(@Nullable String value, String defaultValue) {
        String resolved =
                value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(resolved)) {
            throw new IllegalArgumentException("Invalid status: " + value + "; must be one of " + STATUSES);
        }
        return resolved;
    }

    /** {@code true} for any of the three lifecycle statuses (any casing); false for a blank value. */
    public static boolean isKnownStatus(@Nullable String value) {
        return value != null && STATUSES.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Upper-cased/trimmed reason, or {@code null} when {@code value} is null/blank; else 400. */
    public static @Nullable String normalizeReason(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String resolved = value.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(resolved)) {
            throw new IllegalArgumentException("Invalid outOfServiceReason: " + value + "; must be one of " + REASONS);
        }
        return resolved;
    }

    /** 400 when {@code note} is longer than 255 characters (ADR-0017 shape, DB column width). */
    public static void requireNoteLength(@Nullable String note) {
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw InvalidFieldException.invalid(
                    FIELD_OUT_OF_SERVICE_NOTE, "outOfServiceNote must be at most " + MAX_NOTE_LENGTH + " characters");
        }
    }

    /**
     * DECISION-LOCATION-026 rule 4: going {@code OUT_OF_SERVICE} requires a reason, and {@code
     * OTHER} also requires a non-blank note. A resulting status of {@code ACTIVE} or {@code
     * RETIRED} is never checked here — the caller clears the fields itself before this runs when
     * the status resolves to {@code ACTIVE} (rule 4's "all three clear on return to ACTIVE").
     *
     * @throws InvalidFieldException 422 {@code OUT_OF_SERVICE_REASON_REQUIRED} naming {@code
     *     outOfServiceReason} when absent, or {@code outOfServiceNote} when the reason is {@code
     *     OTHER} and the note is blank
     */
    public static void requireReasonWhenOutOfService(
            String resolvedStatus, @Nullable String reason, @Nullable String note) {
        if (!OUT_OF_SERVICE.equals(resolvedStatus)) {
            return;
        }
        if (reason == null) {
            throw InvalidFieldException.unknownReference(
                    OUT_OF_SERVICE_REASON_REQUIRED,
                    FIELD_OUT_OF_SERVICE_REASON,
                    "outOfServiceReason is required when status is OUT_OF_SERVICE");
        }
        if (OutOfServiceReason.OTHER.name().equals(reason) && (note == null || note.isBlank())) {
            throw InvalidFieldException.unknownReference(
                    OUT_OF_SERVICE_REASON_REQUIRED,
                    FIELD_OUT_OF_SERVICE_NOTE,
                    "outOfServiceNote is required when outOfServiceReason is OTHER");
        }
    }
}
