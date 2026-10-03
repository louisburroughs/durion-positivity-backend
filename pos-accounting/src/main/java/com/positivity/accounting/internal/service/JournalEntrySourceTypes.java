package com.positivity.accounting.internal.service;

/**
 * The {@code journal_entry.source_event_type} each posting path stamps on the entries it creates
 * (#2434). Every entry carries a non-blank source type and a non-null source event id, enforced at
 * {@link JournalEntryService#createJournalEntry}; the pair lets an entry be traced back to the fact
 * or request that produced it and filtered by source.
 *
 * <p>The direct posting paths derive their {@code source_event_id} deterministically from a
 * namespaced domain key ({@code UUID.nameUUIDFromBytes("INVOICE_REVENUE:" + ...)}), which their
 * idempotency lookups depend on. Each type here names that namespace (the posting category), so the
 * pair {@code (source_event_type, source_event_id)} is self-describing and reproducible from the
 * domain key. Entries the posting-rule engine creates carry the accounting event's own type and id
 * instead, and bank-reconciliation adjustments carry their own category.
 */
public final class JournalEntrySourceTypes {

    /**
     * A journal entry created through {@code POST /v1/accounting/journal-entries} without a source
     * of its own. Its source event id is the entry's own id.
     */
    public static final String MANUAL = "MANUAL";

    /**
     * A reversal of a pre-#2434 entry whose category could not be recovered (the V3 backfill left
     * its source type null).
     */
    public static final String LEGACY_REVERSAL = "LEGACY_REVERSAL";

    public static final String CREDIT_MEMO_REVERSAL = "CREDIT_MEMO_REVERSAL";
    public static final String CREDIT_MEMO_VOID = "CREDIT_MEMO_VOID";
    public static final String PAYMENT_APPLICATION = "PAYMENT_APPLICATION";
    public static final String CUSTOMER_CREDIT_ISSUANCE = "CUSTOMER_CREDIT_ISSUANCE";
    public static final String CUSTOMER_CREDIT_RELIEF = "CUSTOMER_CREDIT_RELIEF";
    public static final String INVENTORY_SHRINKAGE = "INVENTORY_SHRINKAGE";
    public static final String INVENTORY_ADJUSTMENT = "INVENTORY_ADJUSTMENT";
    public static final String INVENTORY_REVALUATION = "INVENTORY_REVALUATION";
    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String SETTLEMENT_WRITE_OFF = "SETTLEMENT_WRITE_OFF";
    public static final String SETTLEMENT_RECLASS = "SETTLEMENT_RECLASS";
    public static final String REGISTER_OVER_SHORT = "REGISTER_OVER_SHORT";
    public static final String INVOICE_REVENUE = "INVOICE_REVENUE";
    public static final String INVOICE_REVENUE_REVERSAL = "INVOICE_REVENUE_REVERSAL";

    private JournalEntrySourceTypes() {}
}
