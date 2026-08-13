package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of {@code service.model.RedactionClassification} (ADR-0050 §7): a data
 * classification whose named fields a binding's {@code REDACTED} capture additionally removes.
 *
 * <p>A classification, not a free-text field list, deliberately: §7's minimization requirement is that
 * the same class of data is treated consistently across vendors, and a per-binding list of raw field
 * names would drift vendor by vendor. The field-name vocabulary each classification covers is compiled
 * into {@code PayloadRedactor}, exactly as the credential vocabulary is, so widening a class widens it
 * for every binding that declares it.
 *
 * <p>Name-based only, like all of {@code PayloadRedactor}: a classification finds XML elements and
 * attributes, JSON fields and form parameters. It can do nothing for a positional format — an EDIFACT
 * segment carries values with no names — so for positional families {@code METADATA_ONLY} remains the
 * only level that guarantees nothing is retained; codec-aware positional redaction is CAP-318's.
 */
public enum RedactionClassification {
    /**
     * Customer identity in vendor documents — the §7 example is customer identifiers in fleet
     * workorder authorization payloads: customer/account/fleet/driver numbers, VINs, plates.
     */
    CUSTOMER_IDENTIFIER,
    /**
     * Commercially sensitive pricing — the §7 concern for PRICAT-class documents: unit/net/gross/list
     * prices, discounts, rebates.
     */
    COMMERCIAL_PRICING
}
