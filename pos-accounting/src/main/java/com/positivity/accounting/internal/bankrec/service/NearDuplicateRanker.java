package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Near-duplicate candidates of a bank transaction under duplicate review (SPEC §4.5, D2, D10; story S4, #2303):
 * a fingerprint is a hash with no nearness, so candidate originals are found on the source fields — a row on the
 * same account, other than the subject, not {@code EXCLUDED} or {@code REMOVED_BY_SOURCE}, with the same signed
 * amount (4 dp) and a date within ± the duplicate window. Ranked: equal reference or check number first, then
 * equal normalized description, then smaller date distance, then earlier {@code firstObservedAt}; the top five
 * are served. The rule only proposes; it never changes a status.
 */
public final class NearDuplicateRanker {

    /** How many candidates are served. */
    public static final int LIMIT = 5;

    private NearDuplicateRanker() {}

    /** The ranked candidates of {@code subject} among {@code rows}. */
    public static @NonNull List<BankTransaction> rank(
            @NonNull BankTransaction subject, @NonNull Collection<BankTransaction> rows, int windowDays) {
        return rows.stream()
                .filter(t -> !t.getBankTransactionId().equals(subject.getBankTransactionId()))
                .filter(t -> t.getGlAccountId().equals(subject.getGlAccountId()))
                .filter(t -> t.getStatus() != BankTransactionStatus.EXCLUDED
                        && t.getStatus() != BankTransactionStatus.REMOVED_BY_SOURCE)
                .filter(t -> t.getSignedAmount().compareTo(subject.getSignedAmount()) == 0)
                .filter(t -> distance(subject, t) <= windowDays)
                .sorted(Comparator.comparing((BankTransaction t) -> !sameReference(subject, t))
                        .thenComparing(t -> !sameDescription(subject, t))
                        .thenComparingLong(t -> distance(subject, t))
                        .thenComparing(BankTransaction::getFirstObservedAt, Comparator.nullsLast(Instant::compareTo))
                        .thenComparing(BankTransaction::getBankTransactionId))
                .limit(LIMIT)
                .toList();
    }

    private static long distance(BankTransaction a, BankTransaction b) {
        return Math.abs(ChronoUnit.DAYS.between(a.getTransactionDate(), b.getTransactionDate()));
    }

    private static boolean sameReference(BankTransaction a, BankTransaction b) {
        return present(a.getReference()) && Objects.equals(a.getReference(), b.getReference())
                || present(a.getCheckNumber()) && Objects.equals(a.getCheckNumber(), b.getCheckNumber());
    }

    private static boolean sameDescription(BankTransaction a, BankTransaction b) {
        return present(a.getNormalizedDescription())
                && Objects.equals(a.getNormalizedDescription(), b.getNormalizedDescription());
    }

    private static boolean present(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
