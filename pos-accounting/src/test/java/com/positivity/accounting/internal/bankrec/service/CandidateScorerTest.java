package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.CandidateReason;
import com.positivity.accounting.internal.bankrec.service.CandidateScorer.Score;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The documented candidate scores (SPEC §4.6, §8.3; story S4, #2303, criterion 7). */
@DisplayName("CandidateScorer — deterministic scores and reason codes (#2303)")
class CandidateScorerTest {

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final int W = 7;

    private static LedgerLine ledger(String amount, LocalDate date, String description) {
        return new LedgerLine(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "JE-202609-0001",
                date,
                new BigDecimal(amount),
                description,
                null,
                false);
    }

    private static BankTransaction bank(String amount, LocalDate date, String description, String reference) {
        BankTransaction t = transaction(amount, date);
        t.setDescription(description);
        t.setNormalizedDescription(description.toUpperCase());
        t.setReference(reference);
        return t;
    }

    @Test
    @DisplayName("exact amount, same day, reference and identical description: 60 + 20 + 20 + 10 = 110")
    void perfectCandidate() {
        Score score = CandidateScorer.score(
                bank("250.00", LocalDate.of(2026, 9, 12), "ACH DEPOSIT DEP-7", "DEP-7"),
                ledger("250.00", LocalDate.of(2026, 9, 12), "ACH DEPOSIT DEP-7"),
                W,
                CENT);
        assertThat(score.points()).isEqualTo(110);
        assertThat(score.reasons())
                .containsExactly(
                        CandidateReason.EXACT_AMOUNT,
                        CandidateReason.DATE_IN_WINDOW,
                        CandidateReason.REFERENCE_MATCH,
                        CandidateReason.DESCRIPTION_SIMILAR);
    }

    @Test
    @DisplayName("within tolerance, 3 days apart, nothing else: 40 + round(20 × 4/7) = 51")
    void toleranceAndDistance() {
        Score score = CandidateScorer.score(
                bank("99.99", LocalDate.of(2026, 9, 12), "CARD PAYOUT", null),
                ledger("100.00", LocalDate.of(2026, 9, 9), "Settlement"),
                W,
                CENT);
        assertThat(score.points()).isEqualTo(51);
        assertThat(score.reasons()).containsExactly(CandidateReason.WITHIN_TOLERANCE, CandidateReason.DATE_IN_WINDOW);
        assertThat(score.dateDistance()).isEqualTo(3);
    }

    @Test
    @DisplayName("beyond W scores no date points and is marked DATE_OUT_OF_WINDOW")
    void outsideTheWindow() {
        Score score = CandidateScorer.score(
                bank("100.00", LocalDate.of(2026, 9, 25), "X", null),
                ledger("100.00", LocalDate.of(2026, 9, 2), "Y"),
                W,
                CENT);
        assertThat(score.points()).isEqualTo(60);
        assertThat(score.reasons()).containsExactly(CandidateReason.EXACT_AMOUNT, CandidateReason.DATE_OUT_OF_WINDOW);
    }

    @Test
    @DisplayName("a check number that is a token of the ledger description is a reference match")
    void checkNumberReference() {
        BankTransaction check = bank("-80.00", LocalDate.of(2026, 9, 10), "CHECK", null);
        check.setCheckNumber("1042");
        Score score =
                CandidateScorer.score(check, ledger("-80.00", LocalDate.of(2026, 9, 10), "Check 1042 rent"), W, CENT);
        assertThat(score.reasons()).contains(CandidateReason.REFERENCE_MATCH);
        assertThat(score.points()).isEqualTo(60 + 20 + 20);
    }

    @Test
    @DisplayName("a description overlap below one half scores nothing; at three quarters it scores 8")
    void descriptionSimilarity() {
        Score low = CandidateScorer.score(
                bank("5.00", LocalDate.of(2026, 9, 10), "MONTHLY SERVICE FEE", null),
                ledger("7.00", LocalDate.of(2026, 9, 17), "SERVICE CHARGE ADJ"),
                W,
                CENT);
        assertThat(low.reasons()).doesNotContain(CandidateReason.DESCRIPTION_SIMILAR);
        assertThat(low.points()).isZero();

        Score high = CandidateScorer.score(
                bank("5.00", LocalDate.of(2026, 9, 10), "MONTHLY SERVICE FEE", null),
                ledger("7.00", LocalDate.of(2026, 9, 17), "monthly service fee october"),
                W,
                CENT);
        assertThat(high.reasons()).containsExactly(CandidateReason.DATE_IN_WINDOW, CandidateReason.DESCRIPTION_SIMILAR);
        assertThat(high.points()).isEqualTo(0 + 8);
    }
}
