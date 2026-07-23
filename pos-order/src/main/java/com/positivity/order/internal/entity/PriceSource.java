package com.positivity.order.internal.entity;

public enum PriceSource {
    PRICING_SERVICE,
    CACHE,
    MANUAL,
    /** Imported priced-as-approved from an estimate/workorder (parity story E1, spec R7.1). */
    SOURCE_DOCUMENT
}
