package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchReviewReason;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M1, M2 and the M5 derivation (SPEC §3.4, §4.6, §8.3; story S4, #2303, criteria 7, 16). */
@DisplayName("MatchRules — cardinality, amounts and review reasons (#2303)")
class MatchRulesTest {

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("1:1, 1:N and N:1 have their kinds; N:M is MATCH_CARDINALITY_NOT_ALLOWED [M]")
    void cardinality() {
        assertThat(MatchRules.kind(1, 1)).isEqualTo(MatchKind.ONE_TO_ONE);
        assertThat(MatchRules.kind(1, 2)).isEqualTo(MatchKind.ONE_TO_MANY);
        assertThat(MatchRules.kind(3, 1)).isEqualTo(MatchKind.MANY_TO_ONE);
        assertThatThrownBy(() -> MatchRules.kind(2, 2))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.MATCH_CARDINALITY_NOT_ALLOWED));
    }

    @Test
    @DisplayName("M1: 99.99 against 100.00 agrees; 99.50 is MATCH_AMOUNT_MISMATCH")
    void amountsAgreeWithinOneCent() {
        assertThatCode(() -> MatchRules.requireAmountsAgree(new BigDecimal("99.99"), new BigDecimal("100.00"), CENT))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () -> MatchRules.requireAmountsAgree(new BigDecimal("99.50"), new BigDecimal("100.00"), CENT))
                .isInstanceOf(MatchAmountMismatchException.class);
        assertThatThrownBy(
                        () -> MatchRules.requireAmountsAgree(new BigDecimal("99.98"), new BigDecimal("100.00"), CENT))
                .isInstanceOf(MatchAmountMismatchException.class);
    }

    @Test
    @DisplayName("an exact 1:1 match inside W needs no justification [M]")
    void exactOneToOneNeedsNoReview() {
        assertThat(MatchRules.reviewReasons(
                        MatchKind.ONE_TO_ONE,
                        BigDecimal.ZERO,
                        List.of(LocalDate.of(2026, 9, 12)),
                        List.of(LocalDate.of(2026, 9, 11)),
                        START,
                        7,
                        false))
                .isEmpty();
    }

    @Test
    @DisplayName("1:N, tolerance use, far-apart dates and a former duplicate each need a justification [M]")
    void everyReviewReason() {
        assertThat(MatchRules.reviewReasons(
                        MatchKind.ONE_TO_MANY,
                        BigDecimal.ZERO,
                        List.of(LocalDate.of(2026, 9, 12)),
                        List.of(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12)),
                        START,
                        7,
                        false))
                .containsExactly(MatchReviewReason.CARDINALITY_NOT_ONE_TO_ONE);
        assertThat(MatchRules.reviewReasons(
                        MatchKind.MANY_TO_ONE,
                        CENT,
                        List.of(LocalDate.of(2026, 9, 25)),
                        List.of(LocalDate.of(2026, 9, 2)),
                        START,
                        7,
                        true))
                .containsExactly(
                        MatchReviewReason.CARDINALITY_NOT_ONE_TO_ONE,
                        MatchReviewReason.TOLERANCE_USED,
                        MatchReviewReason.DATE_OUT_OF_WINDOW,
                        MatchReviewReason.FORMER_POSSIBLE_DUPLICATE);
    }

    @Test
    @DisplayName("a carried-in bank row dated before the window start is out of the window")
    void carriedInRowIsOutOfWindow() {
        assertThat(MatchRules.reviewReasons(
                        MatchKind.ONE_TO_ONE,
                        BigDecimal.ZERO,
                        List.of(LocalDate.of(2026, 8, 30)),
                        List.of(LocalDate.of(2026, 9, 2)),
                        START,
                        7,
                        false))
                .containsExactly(MatchReviewReason.DATE_OUT_OF_WINDOW);
    }
}
