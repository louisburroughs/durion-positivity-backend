package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The rules an adjustment is checked against before anything posts (SPEC-manual-bank-reconciliation §3.5, §4.7,
 * D2, D7, D9; story S4, #2303), as pure functions:
 *
 * <ul>
 *   <li>the link rule, in the order §3.5 fixes: a {@code TRANSFER} needs {@code counterGlAccountId} and no
 *       other type may carry one; {@code settlesMatchId} / {@code bridgesStatementId} are {@code OTHER}-only;
 *       an {@code OTHER} has exactly one of its three links — each failure 422 {@code ADJUSTMENT_LINK_REQUIRED};
 *   <li>the {@code OTHER} authority: above {@code BANK_REC_OTHER_APPROVAL_THRESHOLD} it needs
 *       {@code accounting:reconciliation:approve}, at or below {@code adjust} suffices; while the key is unset
 *       every {@code OTHER} except a residual settlement needs {@code approve};
 *   <li>the date rule (D7): the explaining date when its period is open and not hard-locked, else the
 *       caller's date — the period gate then refuses a closed or hard-locked date unless overridden.
 * </ul>
 */
public final class AdjustmentRules {

    private AdjustmentRules() {}

    /** The links a request names. */
    public record Links(
            @Nullable UUID bankTransactionId,
            @Nullable UUID settlesMatchId,
            @Nullable UUID bridgesStatementId,
            @Nullable UUID counterGlAccountId) {

        long otherLinkCount() {
            return Stream.of(bankTransactionId, settlesMatchId, bridgesStatementId)
                    .filter(java.util.Objects::nonNull)
                    .count();
        }
    }

    /**
     * The link rule of §3.5.
     *
     * @throws BankRecException {@code ADJUSTMENT_LINK_REQUIRED} for a missing or forbidden link
     */
    public static void requireLinks(@NonNull BankAdjustmentType type, @NonNull Links links) {
        boolean transfer = type == BankAdjustmentType.TRANSFER;
        if (transfer && links.counterGlAccountId() == null) {
            throw linkRequired("A TRANSFER names its counter bank account in counterGlAccountId", "counterGlAccountId");
        }
        if (!transfer && links.counterGlAccountId() != null) {
            throw linkRequired("counterGlAccountId belongs to a TRANSFER only, not " + type, "counterGlAccountId");
        }
        if (type != BankAdjustmentType.OTHER
                && (links.settlesMatchId() != null || links.bridgesStatementId() != null)) {
            throw linkRequired(
                    "settlesMatchId and bridgesStatementId belong to an OTHER adjustment only, not " + type,
                    links.settlesMatchId() != null ? "settlesMatchId" : "bridgesStatementId");
        }
        if (type == BankAdjustmentType.OTHER && links.otherLinkCount() != 1) {
            throw linkRequired(
                    "An OTHER adjustment names exactly one of bankTransactionId, settlesMatchId and bridgesStatementId"
                            + " (" + links.otherLinkCount() + " given)",
                    "bankTransactionId");
        }
    }

    /**
     * Whether an {@code OTHER} adjustment of {@code amount} needs {@code accounting:reconciliation:approve} (§4.7).
     *
     * @param threshold the tenant's {@code BANK_REC_OTHER_APPROVAL_THRESHOLD}; empty while unset
     * @param residualSettlement whether the adjustment settles a match residual (bounded at one minor unit)
     */
    public static boolean otherNeedsApproval(
            @NonNull BigDecimal amount, @NonNull Optional<BigDecimal> threshold, boolean residualSettlement) {
        return threshold.map(limit -> amount.abs().compareTo(limit) > 0).orElse(!residualSettlement);
    }

    /**
     * The date an adjustment posts at (D7): the explaining date when {@code blocked} says its period is open and
     * not hard-locked; otherwise the caller's date, or — without one — the explaining date, which the period
     * gate then refuses unless an override applies.
     */
    public static @NonNull LocalDate postingDate(
            @NonNull LocalDate explainingDate,
            @Nullable LocalDate requestedDate,
            @NonNull Predicate<LocalDate> blocked) {
        if (!blocked.test(explainingDate)) {
            return explainingDate;
        }
        return requestedDate != null ? requestedDate : explainingDate;
    }

    private static BankRecException linkRequired(String message, String field) {
        return BankRecException.field(BankRecErrorCode.ADJUSTMENT_LINK_REQUIRED, message, field, "link required");
    }
}
