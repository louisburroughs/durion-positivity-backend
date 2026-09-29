package com.positivity.accounting.internal.bankrec.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Normalization and fingerprint rules of the intake (SPEC §3.2, §4.5, §8.1; #2301). */
@DisplayName("TransactionNormalizer")
class TransactionNormalizerTest {

    private static final UUID ACCOUNT = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "ach deposit|ACH DEPOSIT",
                "  ACH   Deposit\t REF  |ACH DEPOSIT REF",
                "Check #1042 - paid.|CHECK 1042 PAID",
                "Café/Bistro, Inc.|CAFÉBISTRO INC",
                "Fee: $5.00|FEE 500",
                "'  '|''"
            })
    void descriptionsAreUpperCasedStrippedAndCollapsed(String raw, String normalized) {
        assertThat(TransactionNormalizer.normalizeDescription(raw)).isEqualTo(normalized);
    }

    @Test
    void aMissingDescriptionNormalizesToEmpty() {
        assertThat(TransactionNormalizer.normalizeDescription(null)).isEmpty();
    }

    @Test
    void aLongDescriptionIsCutToTheColumnWidth() {
        assertThat(TransactionNormalizer.normalizeDescription("A".repeat(600))).hasSize(500);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"1234.5,1234.5000", "-5,-5.0000", "0.0001,0.0001", "12.34000,12.3400"})
    void amountsAreScaledToFourPlacesExactlyAsDelivered(String delivered, String stored) {
        BigDecimal scaled = TransactionNormalizer.scaleAmount(new BigDecimal(delivered));
        assertThat(scaled.toPlainString()).isEqualTo(stored);
        assertThat(scaled.scale()).isEqualTo(4);
    }

    @Test
    void anAmountWithMoreThanFourSignificantDecimalsIsRefusedNotRounded() {
        assertThatThrownBy(() -> TransactionNormalizer.scaleAmount(new BigDecimal("1.00005")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void theFingerprintIsStableAcrossAmountScales() {
        String a = TransactionNormalizer.fingerprint(ACCOUNT, DAY, new BigDecimal("1234.5"), "ACH", "R1", null);
        String b = TransactionNormalizer.fingerprint(ACCOUNT, DAY, new BigDecimal("1234.5000"), "ACH", "R1", null);
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void theFingerprintIsTheSha256OfTheSpecifiedKey() throws Exception {
        String key = ACCOUNT + "|" + DAY + "|1234.5000|ACH|R1";
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(TransactionNormalizer.fingerprint(ACCOUNT, DAY, new BigDecimal("1234.5"), "ACH", "R1", null))
                .isEqualTo(java.util.HexFormat.of().formatHex(digest));
    }

    @Test
    void theReferenceWinsOverTheCheckNumberAndABlankReferenceFallsBack() {
        String withReference = TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "X", "R1", "1042");
        String referenceOnly = TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "X", "R1", null);
        String blankReference = TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "X", " ", "1042");
        String checkOnly = TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "X", null, "1042");
        assertThat(withReference).isEqualTo(referenceOnly);
        assertThat(blankReference).isEqualTo(checkOnly);
    }

    @Test
    void everyKeyPartChangesTheFingerprint() {
        String base = TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "X", "R", null);
        assertThat(TransactionNormalizer.fingerprint(
                        UUID.fromString("5eed0acc-0000-4000-8000-000000001001"), DAY, BigDecimal.TEN, "X", "R", null))
                .isNotEqualTo(base);
        assertThat(TransactionNormalizer.fingerprint(ACCOUNT, DAY.plusDays(1), BigDecimal.TEN, "X", "R", null))
                .isNotEqualTo(base);
        assertThat(TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.ONE, "X", "R", null))
                .isNotEqualTo(base);
        assertThat(TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "Y", "R", null))
                .isNotEqualTo(base);
        assertThat(TransactionNormalizer.fingerprint(ACCOUNT, DAY, BigDecimal.TEN, "X", "S", null))
                .isNotEqualTo(base);
    }
}
