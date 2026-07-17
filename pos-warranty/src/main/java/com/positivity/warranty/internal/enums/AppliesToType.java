package com.positivity.warranty.internal.enums;

/** Matching scope of a policy's {@code appliesTo} terms (PRD §3.2), most specific wins. */
public enum AppliesToType {
    PRODUCT_LIST,
    CATEGORY,
    MANUFACTURER,
    ALL
}
