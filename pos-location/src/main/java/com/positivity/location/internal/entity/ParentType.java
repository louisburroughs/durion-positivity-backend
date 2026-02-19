package com.positivity.location.internal.entity;

/**
 * Types of parent relationships a Location can have.
 *
 * ADR-0016: Each location may have at most ONE parent of each type.
 * Old values (HOME_OFFICE, HEADQUARTERS, REGION, DISTRICT, BILLING) are
 * removed and replaced by these four canonical ADR-0016 values.
 */
public enum ParentType {
        /** Physical containment — e.g., a bay is physically inside a building. */
        PHYSICAL,
        /** Organizational rollup — e.g., a bay belongs to a service department. */
        ORGANIZATIONAL,
        /** Financial rollup — e.g., cost centre or profit centre parent. */
        FINANCIAL,
        /** Logistics/shipping parent — e.g., routes dispatched from this location. */
        SHIPPING
}
