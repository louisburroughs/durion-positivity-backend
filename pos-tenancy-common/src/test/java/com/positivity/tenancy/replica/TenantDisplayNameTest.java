package com.positivity.tenancy.replica;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the one normalization every tenant lookup at login depends on. Each rule the class's
 * javadoc states is asserted here, because a rule that drifts silently makes a tenant unfindable
 * (no error, just no match), and the surrogate and lengthening cases are the ones a casual
 * rewrite would get wrong.
 */
class TenantDisplayNameTest {

    /** U+FB03 LATIN SMALL LIGATURE FFI: NFKC expands it to the three letters {@code ffi}. */
    private static final String FFI_LIGATURE = "ﬃ";

    /** U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE: lower-cases to {@code i} + U+0307, two chars. */
    private static final String DOTTED_CAPITAL_I = "İ";

    /** U+1F600 GRINNING FACE: one code point, two UTF-16 units (a surrogate pair). */
    private static final String EMOJI = "😀";

    @Test
    void normalizeCollapsesWhitespaceTrimsAndCaseFolds() {
        // The javadoc's own example: both spellings must land on the same key.
        assertThat(TenantDisplayName.normalize("Acme  Tire & Auto ")).isEqualTo("acme tire & auto");
        assertThat(TenantDisplayName.normalize("acme tire & auto")).isEqualTo("acme tire & auto");
        assertThat(TenantDisplayName.normalize("\tAcme\n\nTire & Auto")).isEqualTo("acme tire & auto");
    }

    @Test
    void normalizeAppliesNfkcBeforeFolding() {
        // Fullwidth letters and a ligature are compatibility forms; NFKC maps them to plain ASCII.
        assertThat(TenantDisplayName.normalize("ＡＣＭＥ")).isEqualTo("acme");
        assertThat(TenantDisplayName.normalize("O" + FFI_LIGATURE + "ce")).isEqualTo("office");
    }

    @Test
    void displayFormKeepsTheOperatorsCasing() {
        assertThat(TenantDisplayName.displayForm("  Acme   Tire & Auto ")).isEqualTo("Acme Tire & Auto");
        assertThat(TenantDisplayName.displayForm("O" + FFI_LIGATURE + "ce")).isEqualTo("Office");
    }

    @Test
    void valuesWithinTheBoundAreNotTruncated() {
        String exactlyMax = "a".repeat(TenantDisplayName.MAX_LENGTH);

        assertThat(TenantDisplayName.displayForm(exactlyMax)).isEqualTo(exactlyMax);
        assertThat(TenantDisplayName.normalize(exactlyMax)).isEqualTo(exactlyMax);
    }

    @Test
    void valuesOverTheBoundAreCutToMaxLength() {
        String tooLong = "a".repeat(TenantDisplayName.MAX_LENGTH + 50);

        assertThat(TenantDisplayName.displayForm(tooLong)).hasSize(TenantDisplayName.MAX_LENGTH);
        assertThat(TenantDisplayName.normalize(tooLong)).hasSize(TenantDisplayName.MAX_LENGTH);
    }

    @Test
    void truncationStripsWhitespaceLeftAtTheCut() {
        // 199 letters, then a space that lands exactly at the cut, then more text.
        String value = "a".repeat(TenantDisplayName.MAX_LENGTH - 1) + " " + "b".repeat(20);

        String result = TenantDisplayName.displayForm(value);

        assertThat(result).hasSize(TenantDisplayName.MAX_LENGTH - 1).doesNotEndWith(" ");
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        // 199 letters put the emoji's high surrogate at index 199, the last unit kept by a blind cut.
        String value = "a".repeat(TenantDisplayName.MAX_LENGTH - 1) + EMOJI + "b".repeat(20);

        String result = TenantDisplayName.displayForm(value);

        assertThat(result).hasSize(TenantDisplayName.MAX_LENGTH - 1).isEqualTo("a".repeat(199));
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1)))
                .isFalse();
    }

    @Test
    void truncationKeepsAWholeSurrogatePairThatFits() {
        // The pair occupies indices 198-199; index 199 is the LOW surrogate, so nothing is split.
        String value = "a".repeat(TenantDisplayName.MAX_LENGTH - 2) + EMOJI + "b".repeat(20);

        String result = TenantDisplayName.displayForm(value);

        assertThat(result).hasSize(TenantDisplayName.MAX_LENGTH).endsWith(EMOJI);
    }

    @Test
    void nfkcExpansionIsBoundedAfterNormalizing() {
        // 200 ligatures fit the column as typed and become 600 characters after NFKC.
        String value = FFI_LIGATURE.repeat(TenantDisplayName.MAX_LENGTH);

        assertThat(TenantDisplayName.displayForm(value))
                .hasSize(TenantDisplayName.MAX_LENGTH)
                .isEqualTo("ffi".repeat(TenantDisplayName.MAX_LENGTH).substring(0, TenantDisplayName.MAX_LENGTH));
        assertThat(TenantDisplayName.normalize(value)).hasSize(TenantDisplayName.MAX_LENGTH);
    }

    @Test
    void caseFoldExpansionIsBoundedAfterFolding() {
        // 200 dotted capital I's fit the column as typed and become 400 characters after folding.
        String value = DOTTED_CAPITAL_I.repeat(TenantDisplayName.MAX_LENGTH);

        assertThat(DOTTED_CAPITAL_I.toLowerCase(java.util.Locale.ROOT)).hasSize(2);
        assertThat(TenantDisplayName.normalize(value)).hasSize(TenantDisplayName.MAX_LENGTH);
    }
}
