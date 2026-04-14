package com.positivity.tax.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * Supported tax jurisdiction types.
 * <p>
 * Each type exposes a stable code for JSON payloads and an i18n key for localized UI labels.
 */
public enum TaxJurisdictionType {
    COUNTRY("COUNTRY", "tax.jurisdiction.type.country"),
    STATE("STATE", "tax.jurisdiction.type.state"),
    PROVINCE("PROVINCE", "tax.jurisdiction.type.province"),
    COUNTY("COUNTY", "tax.jurisdiction.type.county"),
    CITY("CITY", "tax.jurisdiction.type.city"),
    DISTRICT("DISTRICT", "tax.jurisdiction.type.district"),
    SPECIAL("SPECIAL", "tax.jurisdiction.type.special");

    private final String code;
    private final String i18nKey;

    TaxJurisdictionType(String code, String i18nKey) {
        this.code = code;
        this.i18nKey = i18nKey;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public String i18nKey() {
        return i18nKey;
    }

    @JsonCreator
    public static TaxJurisdictionType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tax jurisdiction type: " + value));
    }
}
