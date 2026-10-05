package com.positivity.accounting.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * BR-1 of #2501: the normalisation behind the vendor-bill duplicate rule (ADR-0070 Decision 4).
 *
 * <p>{@link #br1Examples()} is the story's table, row for row. {@code
 * VendorBillDuplicateRulePostgresIT} runs the same rows through the V4 backfill expression, so the
 * Java normaliser and its SQL twin cannot drift apart unnoticed.
 */
@DisplayName("VendorBillNumbers — the duplicate rule's normalisation (#2501 BR-1)")
public class VendorBillNumbersTest {

    /** Every BR-1 example: the bill number as written, and its key. */
    public static Stream<Arguments> br1Examples() {
        return Stream.of(
                Arguments.of("INV-00123", "INV00123"),
                Arguments.of("inv 00123", "INV00123"),
                Arguments.of("INV/00123", "INV00123"),
                Arguments.of(" Inv.00123 ", "INV00123"),
                // Full-width letters, hyphen and digits: NFKC folds them to their plain forms.
                Arguments.of("ＩＮＶ－００１２３", "INV00123"),
                // Zeros inside the key are kept: not the same bill as INV-00123.
                Arguments.of("INV-123", "INV123"),
                Arguments.of("000123", "123"),
                Arguments.of("123", "123"),
                Arguments.of("12-3", "123"),
                // Leading zeros go only while more than one character remains.
                Arguments.of("0000", "0"),
                Arguments.of("0A-12-b", "A12B"),
                // No letter or digit at all: the empty key, which is legal.
                Arguments.of("#", ""),
                Arguments.of("---", ""));
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @MethodSource("br1Examples")
    @DisplayName("criterion 1: every BR-1 example gives the key in the table")
    void normalisesEveryBr1Example(String billNumber, String key) {
        assertThat(VendorBillNumbers.normalise(billNumber)).isEqualTo(key);
    }

    @Test
    @DisplayName("every separator the rule names is removed")
    void removesEveryNamedSeparator() {
        assertThat(VendorBillNumbers.normalise("a b-c/d.e_f#g:h")).isEqualTo("ABCDEFGH");
    }

    @Test
    @DisplayName("a key is at most 255 characters")
    void keepsAtMost255Characters() {
        String key = VendorBillNumbers.normalise("7".repeat(300));

        assertThat(key).hasSize(VendorBillNumbers.MAX_KEY_LENGTH).isEqualTo("7".repeat(255));
    }

    @Test
    @DisplayName("the cut never splits a character outside the basic plane")
    void cutsOnACodePointBoundary() {
        // U+10400 (Deseret capital long I): a letter NFKC and upper-casing leave alone, two chars in Java.
        String outsideBasicPlane = new String(Character.toChars(0x10400));

        String key = VendorBillNumbers.normalise(outsideBasicPlane.repeat(300));

        assertThat(key.codePointCount(0, key.length())).isEqualTo(VendorBillNumbers.MAX_KEY_LENGTH);
        assertThat(Character.isLowSurrogate(key.charAt(key.length() - 1))).isTrue();
    }

    @Test
    @DisplayName("the entity stores the key whenever the bill number is set")
    void entityStoresTheKeyWithTheNumber() {
        VendorBill bill = new VendorBill();

        bill.setBillNumber("INV-00123");
        assertThat(bill.getBillNumber()).isEqualTo("INV-00123");
        assertThat(bill.getBillNumberKey()).isEqualTo("INV00123");

        bill.setBillNumber("inv 77");
        assertThat(bill.getBillNumberKey()).isEqualTo("INV77");
    }
}
