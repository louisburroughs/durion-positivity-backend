package com.positivity.tax.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The platform's tax-type vocabulary (CAP:550 S32a, louisburroughs/durion-positivity-backend#2636).
 * <p>
 * A tax type names one kind of tax a configured country levies, so that a priced row can be booked
 * by type. The vocabulary is <strong>only</strong> a set of codes: which country uses which type, at
 * what jurisdiction level, under which registration regime, at what rate and whether it is
 * recoverable is all per-country configuration in pos-tax ({@code pos.tax.countries.<country>}),
 * held for expert advice (spec AW48, OI-4). No code branches on a constant of this enum.
 * <p>
 * The first values are those of the first configured country. A later country adds its values
 * additively; consumers store the code as a string, so a new value needs no migration downstream.
 * <p>
 * <strong>Unknown values read as {@code null}.</strong> {@link #fromValue(String)} is the
 * deserializer and returns {@code null} for a code this build does not know, so a pos-tax deploy that
 * adds a value never breaks a reader built before it (Invoicing &amp; Payments sign-off (d)). A reader
 * must treat {@code null} as "untyped", never infer a type.
 */
public enum TaxType {
    GST,
    HST,
    PST,
    QST;

    /**
     * The wire code: the constant name.
     *
     * @return the code
     */
    @JsonValue
    public String code() {
        return name();
    }

    /**
     * Resolves a wire code case-insensitively.
     *
     * @param value the code; may be {@code null}
     * @return the tax type, or {@code null} when the value is blank or unknown to this build
     */
    @JsonCreator
    @Nullable
    public static TaxType fromValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }
}
