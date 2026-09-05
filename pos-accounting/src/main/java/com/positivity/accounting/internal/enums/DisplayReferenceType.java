package com.positivity.accounting.internal.enums;

/**
 * Kinds of UUID-backed reference that accounting can render a human-readable display value for
 * (issues #1778, #1779).
 *
 * <p>Every value is resolved from data accounting already holds — its own records, or a
 * {@code customer.events.v1} / {@code invoice.events.v1} replica — so no display resolution
 * crosses a domain wall (ADR-0044). A type whose source is absent resolves to null display
 * values; a UUID is never substituted as display text.
 */
public enum DisplayReferenceType {

    /** Invoice, resolved from the {@code ext_invoice} replica's invoice number. */
    INVOICE,

    /** Customer party, resolved from the {@code ext_customer_party} replica's name and number. */
    CUSTOMER,

    /**
     * Organization scope key. Recognized so the contract covers it, but always unresolved today:
     * ADR-0023 retired multi-tenancy and no organization directory exists on the platform, so
     * there is nothing to name it from. The day one exists, only the resolver changes — not the
     * wire contract.
     */
    ORGANIZATION,

    /**
     * Location dimension value, resolved from {@code accounting_location_profile}. That table is
     * keyed by the accounting location <em>code</em> ({@code LOC-107}), so a payload carrying a
     * raw location UUID resolves to null unless a profile is coded with that same string.
     */
    LOCATION,

    /** Journal entry, resolved from this module's own {@code journal_entry.entry_number}. */
    JOURNAL_ENTRY,

    /** AP vendor, resolved from this module's own {@code vendor} name and number. */
    VENDOR,

    /** AP vendor bill, resolved from this module's own {@code vendor_bill} bill number. */
    VENDOR_BILL
}
