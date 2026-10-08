package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReceiptUnitCosts — document price to per-base-unit ledger cost (#2203)")
class ReceiptUnitCostsTest {

    @Test
    @DisplayName("a base-unit price moves from minor to major units at the ledger scale")
    void basePrice_convertsMinorToMajor() {
        assertThat(ReceiptUnitCosts.perBaseUnit(1_250L, null, "USD")).isEqualTo(new BigDecimal("12.5000"));
        assertThat(ReceiptUnitCosts.perBaseUnit(1_250L, BigDecimal.ONE, "USD")).isEqualTo(new BigDecimal("12.5000"));
    }

    @Test
    @DisplayName("a document-unit price is divided by the base units it prices")
    void documentPrice_dividesByConversionFactor() {
        assertThat(ReceiptUnitCosts.perBaseUnit(12_000L, new BigDecimal("12"), "USD"))
                .isEqualTo(new BigDecimal("10.0000"));
        // 10.00 per pack of 3 → 3.3333 each, HALF_UP at scale 4
        assertThat(ReceiptUnitCosts.perBaseUnit(1_000L, new BigDecimal("3"), "USD"))
                .isEqualTo(new BigDecimal("3.3333"));
    }

    @Test
    @DisplayName("the currency decides the minor-unit digits; unknown or absent falls back to two")
    void currencyDigits() {
        assertThat(ReceiptUnitCosts.perBaseUnit(1_250L, null, "JPY")).isEqualTo(new BigDecimal("1250.0000"));
        assertThat(ReceiptUnitCosts.perBaseUnit(1_250L, null, "BHD")).isEqualTo(new BigDecimal("1.2500"));
        assertThat(ReceiptUnitCosts.perBaseUnit(1_250L, null, null)).isEqualTo(new BigDecimal("12.5000"));
        assertThat(ReceiptUnitCosts.perBaseUnit(1_250L, null, "NOPE")).isEqualTo(new BigDecimal("12.5000"));
    }

    @Test
    @DisplayName("no price, or a non-positive factor, yields no cost")
    void noPriceOrBadFactor_yieldsNull() {
        assertThat(ReceiptUnitCosts.perBaseUnit(null, null, "USD")).isNull();
        assertThat(ReceiptUnitCosts.perBaseUnit(1_000L, BigDecimal.ZERO, "USD")).isNull();
    }

    @Test
    @DisplayName("S41 (#2602): a half at the ledger scale rounds HALF_UP, not to even")
    void documentPrice_halfAtLedgerScale_roundsHalfUp() {
        // 0.25 per pack of 8 = 0.03125 each: HALF_EVEN would keep 0.0312, HALF_UP gives 0.0313.
        assertThat(ReceiptUnitCosts.perBaseUnit(25L, new BigDecimal("8"), "USD"))
                .isEqualTo(new BigDecimal("0.0313"));
    }

    @Test
    @DisplayName("S41 (#2602): an inventory value goes to minor units HALF_UP, at the currency's digits")
    void toMinorUnits_roundsHalfUp() {
        // 2.5 x 1.01 = 2.525 -> 253, where HALF_EVEN would give 252.
        assertThat(ReceiptUnitCosts.toMinorUnits(new BigDecimal("2.5").multiply(new BigDecimal("1.0100")), "USD"))
                .isEqualTo(253L);
        assertThat(ReceiptUnitCosts.toMinorUnits(new BigDecimal("380.0000"), "USD"))
                .isEqualTo(38_000L);
        assertThat(ReceiptUnitCosts.toMinorUnits(new BigDecimal("12.5"), "JPY")).isEqualTo(13L);
    }
}
