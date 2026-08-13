package com.positivity.supplier.service.model;

/**
 * A data classification whose named fields a binding's {@code REDACTED} capture additionally removes
 * from stored exchange payloads (ADR-0050 §7 minimization). Declared per binding alongside the capture
 * level, so redaction configuration lives with the binding.
 *
 * <p>A classification, not a free-text field list: the field-name vocabulary each class covers is
 * defined once in the redactor, so the same class of data is treated consistently across vendors.
 * Classifications only find <em>named</em> fields (XML elements/attributes, JSON fields, form
 * parameters); for positional vendor formats {@code METADATA_ONLY} remains the only capture level that
 * guarantees nothing is retained.
 */
public enum RedactionClassification {
    /**
     * Customer identity in vendor documents — e.g. customer identifiers in fleet workorder
     * authorization payloads: customer/account/fleet/driver numbers, VINs, plates.
     */
    CUSTOMER_IDENTIFIER,
    /**
     * Commercially sensitive pricing — e.g. PRICAT content: unit/net/gross/list prices, discounts,
     * rebates.
     */
    COMMERCIAL_PRICING
}
