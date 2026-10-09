package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CAP:550 S32d item 7 and AC 25: pos-order's copy of pos-tax's normalisation, and the drawer's local check. */
@DisplayName("SupplierRegistrationNumbers (CAP:550 S32d)")
class SupplierRegistrationNumbersTest {

    /**
     * CAP:550 S32d AC 25: the normalisation matrix. The SAME rows, inputs and outputs, run in pos-tax's
     * {@code RegistrationNumberShapesTest} and pos-order's {@code SupplierRegistrationNumbersTest}, so the two copies of
     * the rule cannot drift (Order acknowledgement 2026-10-08). Changing one table without the other is a review
     * failure.
     */
    static Stream<Arguments> normalisationMatrix() {
        return Stream.of(
                Arguments.of(" 000 000 000-rt-0001 ", "000000000RT0001"),
                Arguments.of("000 000 000 rt 0001", "000000000RT0001"),
                Arguments.of("000000000rt0001", "000000000RT0001"),
                Arguments.of("\t000000000RT0001\n", "000000000RT0001"),
                Arguments.of("000000000\tRT0001", "000000000\tRT0001"),
                Arguments.of("000000000\u00A0RT0001", "000000000\u00A0RT0001"),
                Arguments.of("\u2003000000000RT0001", "\u2003000000000RT0001"),
                Arguments.of("000000000\u2013RT0001", "000000000\u2013RT0001"),
                Arguments.of("\u00e9-z", "\u00e9Z"),
                Arguments.of("- -", ""));
    }

    @ParameterizedTest(name = "[{index}] normalize")
    @MethodSource("normalisationMatrix")
    @DisplayName("AC 25: the normalisation matrix shared with pos-tax")
    void normalisationMatrixSharedWithPosTax(String input, String expected) {
        assertThat(SupplierRegistrationNumbers.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("AC 25: the local check accepts the normalised number and refuses an interior tab or no-break space")
    void localCheck() {
        assertThat(SupplierRegistrationNumbers.locallyAccepted(" 000 000 000-rt-0001 "))
                .contains("000000000RT0001");
        assertThat(SupplierRegistrationNumbers.locallyAccepted("000000000\tRT0001"))
                .isEmpty();
        assertThat(SupplierRegistrationNumbers.locallyAccepted("000000000\u00A0RT0001"))
                .isEmpty();
        assertThat(SupplierRegistrationNumbers.locallyAccepted("\u2003000000000RT0001"))
                .isEmpty();
        // An en dash passes here; pos-tax's shape check answers it.
        assertThat(SupplierRegistrationNumbers.locallyAccepted("000000000\u2013RT0001"))
                .isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "- -"})
    @DisplayName("an empty number after normalising fails the local check")
    void emptyFails(String number) {
        assertThat(SupplierRegistrationNumbers.locallyAccepted(number)).isEmpty();
    }

    @Test
    @DisplayName("32 characters pass and 33 fail, counted after normalising")
    void lengthIsCountedAfterNormalising() {
        assertThat(SupplierRegistrationNumbers.locallyAccepted("0".repeat(32))).isPresent();
        assertThat(SupplierRegistrationNumbers.locallyAccepted("0".repeat(33))).isEmpty();
        assertThat(SupplierRegistrationNumbers.locallyAccepted("0-".repeat(32))).contains("0".repeat(32));
        assertThat(SupplierRegistrationNumbers.locallyAccepted(null)).isEmpty();
    }
}
