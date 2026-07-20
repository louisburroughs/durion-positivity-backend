package com.positivity.tax.common.enums;

/**
 * Direction/intent of a tax calculation (Odoo parity, story T4).
 * <p>
 * {@link #SALE} is the default forward calculation. {@link #REFUND} computes the
 * tax to reverse for a partial/line-level refund or a provider refund document:
 * amounts are computed <strong>positive</strong> (callers negate at posting, which
 * keeps {@code @Positive} request validation intact and avoids sign bugs) and rates
 * are resolved as of the {@code transactionDate} the caller supplies — for a refund
 * that is the <em>original sale date</em>, so effective-dated rates (story T2)
 * reprice correctly.
 * <p>
 * Note the platform rule: finalized-invoice credits use the <em>stored</em> tax
 * breakdown and must never be recomputed; {@code REFUND} mode is only for
 * partial/line-level refunds where a fresh proration is genuinely needed and for
 * provider-side refund documents (freeze-after-finalize).
 */
public enum TaxCalculationType {
    /** Forward sale calculation (default). */
    SALE,
    /** Refund/credit calculation: positive amounts priced at the original sale date. */
    REFUND
}
