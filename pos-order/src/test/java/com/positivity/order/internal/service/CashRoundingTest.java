package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ADR-0067 A8 (#2662, Order ruling §1 and AC (b)-(d)): HALF_UP to the increment at the currency's exponent. */
class CashRoundingTest {

    static Stream<Arguments> cadNickel() {
        return Stream.of(
                Arguments.of("9.98", "10.00", "0.02"),
                Arguments.of("9.97", "9.95", "-0.02"),
                Arguments.of("10.02", "10.00", "-0.02"),
                Arguments.of("10.03", "10.05", "0.02"),
                Arguments.of("10.00", "10.00", "0.00"),
                Arguments.of("0.01", "0.00", "-0.01"),
                Arguments.of("0.03", "0.05", "0.02"));
    }

    @ParameterizedTest(name = "CAD due {0} -> cash {1}, rounding {2}")
    @MethodSource("cadNickel")
    void roundsToTheNickelHalfUp(String due, String cash, String rounding) {
        CashRounding.Rounded r = CashRounding.round(new BigDecimal(due), new BigDecimal("0.05"), 2);

        assertThat(r.amountDue()).isEqualByComparingTo(due);
        assertThat(r.cashDue()).isEqualByComparingTo(cash);
        assertThat(r.cashRoundingAmount()).isEqualByComparingTo(rounding);
        assertThat(r.cashDue().scale()).isEqualTo(2);
        assertThat(r.cashIncrement()).isEqualByComparingTo("0.05");
    }

    @Test
    void aTenCentIncrementRoundsTheHalfUp() {
        CashRounding.Rounded r = CashRounding.round(new BigDecimal("10.05"), new BigDecimal("0.10"), 2);

        assertThat(r.cashDue()).isEqualByComparingTo("10.10");
        assertThat(r.cashRoundingAmount()).isEqualByComparingTo("0.05");
    }

    @Test
    void aWholeUnitIncrementAtAZeroExponentCurrency() {
        CashRounding.Rounded r = CashRounding.round(new BigDecimal("1234"), new BigDecimal("5"), 0);

        assertThat(r.cashDue()).isEqualByComparingTo("1235");
        assertThat(r.cashRoundingAmount()).isEqualByComparingTo("1");
    }

    @Test
    void noIncrementIsANoOp() {
        CashRounding.Rounded r = CashRounding.round(new BigDecimal("9.98"), null, 2);

        assertThat(r.cashDue()).isEqualByComparingTo("9.98");
        assertThat(r.cashRoundingAmount()).isZero();
        assertThat(r.cashIncrement()).isNull();
    }

    @Test
    void multipleOfTheIncrement() {
        assertThat(CashRounding.isMultipleOf(new BigDecimal("10.00"), new BigDecimal("0.05"))).isTrue();
        assertThat(CashRounding.isMultipleOf(new BigDecimal("10.02"), new BigDecimal("0.05"))).isFalse();
        assertThat(CashRounding.isMultipleOf(new BigDecimal("10.02"), null)).isTrue();
    }
}
