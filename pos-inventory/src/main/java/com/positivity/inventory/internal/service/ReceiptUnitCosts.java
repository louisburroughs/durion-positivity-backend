package com.positivity.inventory.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Turns a receipt's document price into the per-base-unit cost a {@code GOODS_RECEIPT} ledger row
 * carries (#2203, ADR-0048 IMP-002). The costing engine reads that cost as the document cost:
 * AVERAGE blends it into the running average and STANDARD keeps it as its latest-receipt memo.
 */
public final class ReceiptUnitCosts {

    /** Scale of {@code inventory_ledger.unit_cost}, {@code numeric(19,4)}. */
    public static final int LEDGER_COST_SCALE = 4;

    /** Minor-unit digits when the currency is unknown, matching the module's other price math. */
    private static final int DEFAULT_FRACTION_DIGITS = 2;

    private ReceiptUnitCosts() {}

    /**
     * The cost of one base unit, in major units at the ledger scale.
     *
     * @param unitCostMinor    the price of one priced unit, in minor units of {@code currency}
     * @param conversionFactor base units per priced unit; null or one when the price is per base unit
     * @param currency         ISO 4217 code deciding the minor-unit digits; two when null or unknown
     * @return the per-base-unit cost, or null when there is no price to convert
     */
    public static @Nullable BigDecimal perBaseUnit(
            @Nullable Long unitCostMinor, @Nullable BigDecimal conversionFactor, @Nullable String currency) {
        if (unitCostMinor == null) {
            return null;
        }
        BigDecimal major = BigDecimal.valueOf(unitCostMinor).movePointLeft(fractionDigits(currency));
        if (conversionFactor == null || conversionFactor.compareTo(BigDecimal.ONE) == 0) {
            return major.setScale(LEDGER_COST_SCALE, RoundingMode.HALF_EVEN);
        }
        if (conversionFactor.signum() <= 0) {
            return null;
        }
        return major.divide(conversionFactor, LEDGER_COST_SCALE, RoundingMode.HALF_EVEN);
    }

    private static int fractionDigits(@Nullable String currency) {
        if (currency == null || currency.isBlank()) {
            return DEFAULT_FRACTION_DIGITS;
        }
        try {
            int digits = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT))
                    .getDefaultFractionDigits();
            return digits < 0 ? DEFAULT_FRACTION_DIGITS : digits;
        } catch (IllegalArgumentException unknownCurrency) {
            return DEFAULT_FRACTION_DIGITS;
        }
    }
}
