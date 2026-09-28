package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.CandidateReason;
import com.positivity.accounting.internal.bankrec.intake.TransactionNormalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic, explainable match scoring (SPEC §4.6; story S4, #2303) — no model:
 *
 * <table>
 *   <caption>Signals</caption>
 *   <tr><td>exact signed amount</td><td>+60</td><td>EXACT_AMOUNT</td></tr>
 *   <tr><td>amount within ±0.01</td><td>+40</td><td>WITHIN_TOLERANCE</td></tr>
 *   <tr><td>date distance d ≤ W</td><td>+20 × (1 − d / W)</td><td>DATE_IN_WINDOW</td></tr>
 *   <tr><td>d beyond W (window widened)</td><td>+0</td><td>DATE_OUT_OF_WINDOW</td></tr>
 *   <tr><td>reference or check number is a ledger description token</td><td>+20</td><td>REFERENCE_MATCH</td></tr>
 *   <tr><td>description Jaccard J ≥ 0.5</td><td>+10 × J</td><td>DESCRIPTION_SIMILAR</td></tr>
 * </table>
 *
 * <p>The amount signals are exclusive: an exact amount scores 60, not 100. Partial points round half up.
 */
public final class CandidateScorer {

    static final int EXACT_POINTS = 60;
    static final int TOLERANCE_POINTS = 40;
    static final int DATE_POINTS = 20;
    static final int REFERENCE_POINTS = 20;
    static final int DESCRIPTION_POINTS = 10;
    static final double DESCRIPTION_THRESHOLD = 0.5;

    private CandidateScorer() {}

    /** The score of pairing one bank transaction with one ledger line, and why. */
    public record Score(int points, @NonNull List<CandidateReason> reasons, long dateDistance) {}

    /**
     * Scores a bank transaction against a ledger line.
     *
     * @param windowDays W, the configured date window; a larger distance scores no date points and is marked
     *     {@link CandidateReason#DATE_OUT_OF_WINDOW}
     */
    public static @NonNull Score score(
            @NonNull BankTransaction bank, @NonNull LedgerLine line, int windowDays, @NonNull BigDecimal tolerance) {
        List<CandidateReason> reasons = new ArrayList<>();
        int points = 0;

        BigDecimal difference =
                bank.getSignedAmount().subtract(line.signedAmount()).abs();
        if (difference.signum() == 0) {
            points += EXACT_POINTS;
            reasons.add(CandidateReason.EXACT_AMOUNT);
        } else if (difference.compareTo(tolerance) <= 0) {
            points += TOLERANCE_POINTS;
            reasons.add(CandidateReason.WITHIN_TOLERANCE);
        }

        long distance = Math.abs(ChronoUnit.DAYS.between(bank.getTransactionDate(), line.date()));
        if (distance <= windowDays) {
            points += BigDecimal.valueOf(DATE_POINTS)
                    .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(distance)
                            .divide(BigDecimal.valueOf(windowDays), 10, RoundingMode.HALF_UP)))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();
            reasons.add(CandidateReason.DATE_IN_WINDOW);
        } else {
            reasons.add(CandidateReason.DATE_OUT_OF_WINDOW);
        }

        Set<String> ledgerTokens = tokens(ledgerText(line));
        if (referenceMatches(bank.getReference(), ledgerTokens)
                || referenceMatches(bank.getCheckNumber(), ledgerTokens)) {
            points += REFERENCE_POINTS;
            reasons.add(CandidateReason.REFERENCE_MATCH);
        }

        double jaccard = jaccard(tokens(bankText(bank)), ledgerTokens);
        if (jaccard >= DESCRIPTION_THRESHOLD) {
            points += (int) Math.round(DESCRIPTION_POINTS * jaccard);
            reasons.add(CandidateReason.DESCRIPTION_SIMILAR);
        }
        return new Score(points, List.copyOf(reasons), distance);
    }

    /** Jaccard similarity of two token sets; 0 when either is empty. */
    static double jaccard(@NonNull Set<String> a, @NonNull Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /** The normalized tokens of a text (upper case, punctuation stripped, whitespace split). */
    static @NonNull Set<String> tokens(@Nullable String text) {
        String normalized = TransactionNormalizer.normalizeDescription(text);
        if (normalized.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(normalized.split(" ")));
    }

    private static boolean referenceMatches(@Nullable String reference, Set<String> ledgerTokens) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        Set<String> referenceTokens = tokens(reference);
        return !referenceTokens.isEmpty() && ledgerTokens.containsAll(referenceTokens);
    }

    private static String bankText(BankTransaction bank) {
        return bank.getNormalizedDescription() != null ? bank.getNormalizedDescription() : bank.getDescription();
    }

    private static String ledgerText(LedgerLine line) {
        return String.join(" ", nullToEmpty(line.description()), nullToEmpty(line.entryDescription()));
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
