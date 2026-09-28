package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchReviewReason;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * The match invariants a human match is checked against (SPEC §3.4, §4.6; story S4, #2303): M1 (sides agree
 * within one minor unit), M2 (exactly one side may have more than one member — N:M is refused, C6) and the
 * M5 reasons that require a justification.
 */
public final class MatchRules {

    private MatchRules() {}

    /**
     * The kind of a human match from its cardinality (M2).
     *
     * @throws BankRecException {@code MATCH_CARDINALITY_NOT_ALLOWED} for N:M
     */
    public static @NonNull MatchKind kind(int bankMembers, int ledgerMembers) {
        if (bankMembers > 1 && ledgerMembers > 1) {
            throw new BankRecException(
                    BankRecErrorCode.MATCH_CARDINALITY_NOT_ALLOWED,
                    "A match may have more than one member on one side only; split " + bankMembers + " bank × "
                            + ledgerMembers + " ledger into two matches");
        }
        if (bankMembers == 1 && ledgerMembers == 1) {
            return MatchKind.ONE_TO_ONE;
        }
        return bankMembers == 1 ? MatchKind.ONE_TO_MANY : MatchKind.MANY_TO_ONE;
    }

    /**
     * M1: the sides agree within one minor unit; a larger residual is an adjustment, not a match.
     *
     * @throws MatchAmountMismatchException when they do not
     */
    public static void requireAmountsAgree(
            @NonNull BigDecimal bankTotal, @NonNull BigDecimal ledgerTotal, @NonNull BigDecimal tolerance) {
        if (bankTotal.subtract(ledgerTotal).abs().compareTo(tolerance) > 0) {
            throw new MatchAmountMismatchException("Bank transactions net " + bankTotal + " but ledger lines net "
                    + ledgerTotal + " (must agree within ±" + tolerance.toPlainString() + ")");
        }
    }

    /**
     * The M5 reasons of a match: non-1:1, tolerance used, a member dated before the window start or a bank
     * and a ledger member more than {@code windowDays} apart, a former possible duplicate.
     */
    public static @NonNull Set<MatchReviewReason> reviewReasons(
            @NonNull MatchKind kind,
            @NonNull BigDecimal toleranceUsed,
            @NonNull Collection<LocalDate> bankDates,
            @NonNull Collection<LocalDate> ledgerDates,
            @NonNull LocalDate windowStart,
            int windowDays,
            boolean formerPossibleDuplicate) {
        Set<MatchReviewReason> reasons = EnumSet.noneOf(MatchReviewReason.class);
        if (kind == MatchKind.ONE_TO_MANY || kind == MatchKind.MANY_TO_ONE) {
            reasons.add(MatchReviewReason.CARDINALITY_NOT_ONE_TO_ONE);
        }
        if (toleranceUsed.signum() != 0) {
            reasons.add(MatchReviewReason.TOLERANCE_USED);
        }
        boolean beforeWindow = bankDates.stream().anyMatch(d -> d.isBefore(windowStart))
                || ledgerDates.stream().anyMatch(d -> d.isBefore(windowStart));
        boolean farApart = bankDates.stream()
                .anyMatch(b ->
                        ledgerDates.stream().anyMatch(l -> Math.abs(ChronoUnit.DAYS.between(b, l)) > windowDays));
        if (beforeWindow || farApart) {
            reasons.add(MatchReviewReason.DATE_OUT_OF_WINDOW);
        }
        if (formerPossibleDuplicate) {
            reasons.add(MatchReviewReason.FORMER_POSSIBLE_DUPLICATE);
        }
        return reasons;
    }
}
