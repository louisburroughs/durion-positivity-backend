package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Near-duplicate candidates (SPEC §4.5, §8.4; story S4, #2303, criterion 16). */
@DisplayName("NearDuplicateRanker (#2303)")
class NearDuplicateRankerTest {

    private static BankTransaction row(String amount, int day, String description, String reference) {
        BankTransaction t = transaction(amount, LocalDate.of(2026, 9, day));
        t.setDescription(description);
        t.setNormalizedDescription(description);
        t.setReference(reference);
        t.setFirstObservedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return t;
    }

    @Test
    @DisplayName(
            "same reference first, then same description, then date distance; wrong amount, day 16 and EXCLUDED never")
    void rankingAndExclusions() {
        BankTransaction subject = row("250.00", 12, "ACH DEPOSIT", "DEP-7");
        BankTransaction sameReference = row("250.00", 10, "INCOMING TRANSFER", "DEP-7");
        BankTransaction sameDescription = row("250.00", 12, "ACH DEPOSIT", null);
        BankTransaction neither = row("250.00", 15, "WIRE", null);
        BankTransaction centOff = row("250.01", 12, "ACH DEPOSIT", "DEP-7");
        BankTransaction tooLate = row("250.00", 16, "ACH DEPOSIT", "DEP-7");
        BankTransaction excluded = row("250.00", 12, "ACH DEPOSIT", "DEP-7");
        excluded.setStatus(BankTransactionStatus.EXCLUDED);

        List<BankTransaction> ranked = NearDuplicateRanker.rank(
                subject, List.of(neither, excluded, tooLate, sameDescription, centOff, sameReference, subject), 3);

        assertThat(ranked).containsExactly(sameReference, sameDescription, neither);
    }

    @Test
    @DisplayName("ties break on the earlier firstObservedAt; at most five are served")
    void tiesAndLimit() {
        BankTransaction subject = row("40.00", 10, "FEE", null);
        BankTransaction later = row("40.00", 11, "OTHER", null);
        later.setFirstObservedAt(Instant.parse("2026-10-02T00:00:00Z"));
        BankTransaction earlier = row("40.00", 9, "OTHER", null);
        earlier.setFirstObservedAt(Instant.parse("2026-09-30T00:00:00Z"));
        assertThat(NearDuplicateRanker.rank(subject, List.of(later, earlier), 3))
                .containsExactly(earlier, later);

        List<BankTransaction> many = List.of(
                row("40.00", 10, "A", null),
                row("40.00", 10, "B", null),
                row("40.00", 10, "C", null),
                row("40.00", 10, "D", null),
                row("40.00", 10, "E", null),
                row("40.00", 10, "F", null));
        assertThat(NearDuplicateRanker.rank(subject, many, 3)).hasSize(5);
    }
}
