package com.positivity.accounting.internal.enums;

/**
 * Kinds of reference that accounting can render a human-readable display value for
 * (issues #1778, #1779, #1797).
 *
 * <p>Every value is resolved from data accounting already holds — its own records, or a
 * {@code customer.events.v1} / {@code invoice.events.v1} replica — so no display resolution
 * crosses a domain wall (ADR-0044). A type whose source is absent resolves to null display
 * values; an identifier is never substituted as display text.
 *
 * <p>Most types are keyed by UUID. {@link #LOCATION} is keyed by the accounting location
 * <em>code</em> instead, because that is what the accounting location dimension carries; see
 * {@link #isCodeKeyed()}.
 */
public enum DisplayReferenceType {

    /** Invoice, resolved from the {@code ext_invoice} replica's invoice number. */
    INVOICE(false),

    /** Customer party, resolved from the {@code ext_customer_party} replica's name and number. */
    CUSTOMER(false),

    /**
     * Organization scope key. Recognized so the contract covers it, but always unresolved today:
     * ADR-0023 retired multi-tenancy and no organization directory exists on the platform, so
     * there is nothing to name it from. The day one exists, only the resolver changes — not the
     * wire contract.
     */
    ORGANIZATION(false),

    /**
     * Location dimension value, resolved from {@code accounting_location_profile}. Accounting's
     * location dimension is code-keyed ({@code LOC-107}, {@code LOC_USA}) rather than UUID-backed,
     * so this type is matched by code — case-insensitively against
     * {@code accounting_location_profile.location_code} — and a payload value need not parse as a
     * UUID to be projected (issue #1797).
     */
    LOCATION(true),

    /** Journal entry, resolved from this module's own {@code journal_entry.entry_number}. */
    JOURNAL_ENTRY(false),

    /** AP vendor, resolved from this module's own {@code vendor} name and number. */
    VENDOR(false),

    /** AP vendor bill, resolved from this module's own {@code vendor_bill} bill number. */
    VENDOR_BILL(false);

    private final boolean codeKeyed;

    DisplayReferenceType(boolean codeKeyed) {
        this.codeKeyed = codeKeyed;
    }

    /**
     * Whether references of this type are identified by a business code rather than a UUID.
     *
     * <p>A code-keyed type is resolved through
     * {@code DisplayReferenceResolver#resolveCodes}; a UUID-keyed type through
     * {@code DisplayReferenceResolver#resolve}. The projection keeps the raw payload value for
     * both, and carries a parsed UUID only when the value is one.
     */
    public boolean isCodeKeyed() {
        return codeKeyed;
    }
}
