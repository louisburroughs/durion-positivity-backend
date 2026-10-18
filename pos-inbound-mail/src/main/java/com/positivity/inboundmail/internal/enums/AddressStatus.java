package com.positivity.inboundmail.internal.enums;

/** Lifecycle of an intake address: one {@code ACTIVE} per tenant, every older one {@code RETIRED}. */
public enum AddressStatus {
    ACTIVE,
    RETIRED
}
