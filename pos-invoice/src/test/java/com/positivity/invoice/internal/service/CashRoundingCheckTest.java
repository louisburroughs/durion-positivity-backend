package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.invoice.internal.exception.CashRoundingInvalidException;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ADR-0067 A8 (#2662, ruling D3): the rounding triple a register sends must hold together. */
class CashRoundingCheckTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    static Stream<Arguments> validTriples() {
        return Stream.of(
                Arguments.of("CAD 149.98 rounds up to 150.00", "149.98", "150.00", "0.02", "0.05"),
                Arguments.of("CAD 149.92 rounds down to 149.90", "149.92", "149.90", "-0.02", "0.05"),
                Arguments.of("CAD half-way 149.925 rounds up", "149.925", "149.95", "0.025", "0.05"),
                Arguments.of("CAD exact multiple", "150.00", "150.00", "0.00", "0.05"),
                Arguments.of("CHF 10.02 rounds to 10.00", "10.02", "10.00", "-0.02", "0.05"),
                Arguments.of("SEK whole krona 19.50 rounds up to 20", "19.50", "20", "0.50", "1"),
                Arguments.of("USD without an increment", "149.99", "149.99", "0.00", null),
                Arguments.of("USD without an increment, scale differs", "149.99", "149.990", "0", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validTriples")
    void acceptsATripleTheRegisterRoundedHalfUp(
            String name, String amount, String settled, String rounding, String increment) {
        assertThatCode(() -> CashRoundingCheck.verify(
                        d(amount), d(settled), d(rounding), increment == null ? null : d(increment)))
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> invalidTriples() {
        return Stream.of(
                Arguments.of("settled not a multiple of the increment", "149.98", "149.98", "0.00", "0.05", "HALF_UP"),
                Arguments.of("rounding not settled minus amount", "149.98", "150.00", "0.03", "0.05", "minus amount"),
                Arguments.of("rounded the wrong way", "149.98", "149.95", "-0.03", "0.05", "HALF_UP"),
                Arguments.of("rounded a whole increment away", "149.98", "150.05", "0.07", "0.05", "HALF_UP"),
                Arguments.of("half-way rounded down (HALF_UP owed)", "149.925", "149.90", "-0.025", "0.05", "HALF_UP"),
                Arguments.of("zero increment", "149.98", "149.98", "0.00", "0", "greater than zero"),
                Arguments.of("negative increment", "149.98", "149.98", "0.00", "-0.05", "greater than zero"),
                Arguments.of("no increment but settled differs", "149.99", "150.00", "0.01", null, "settledAmount"),
                Arguments.of(
                        "no increment but rounding non-zero", "149.99", "149.99", "0.01", null, "cashRoundingAmount"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTriples")
    @DisplayName("refuses a triple that does not hold together")
    void refusesATripleThatDoesNotHoldTogether(
            String name, String amount, String settled, String rounding, String increment, String messagePart) {
        assertThatThrownBy(() -> CashRoundingCheck.verify(
                        d(amount), d(settled), d(rounding), increment == null ? null : d(increment)))
                .isInstanceOf(CashRoundingInvalidException.class)
                .hasMessageContaining(messagePart);
    }
}
