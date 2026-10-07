package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.PettyExpenseCategoryChangeType;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A petty-expense category as the Approval limits page shows it (#2511; S21): code, label, examples, status,
 * the account it posts to today, any later-dated account, and its change history.
 */
@Schema(description = "A petty-expense category with its accounts and history")
public record PettyExpenseCategoryResponse(
        @Schema(description = "Permanent code", example = "STAFF_MEALS")
        String code,

        @Schema(description = "The label the cashier sees", example = "Staff meals")
        String label,

        @Schema(description = "What belongs in the category") @Nullable
        String examples,

        @Schema(description = "ACTIVE or INACTIVE (terminal)")
        PettyExpenseCategoryStatus status,

        @Schema(description = "The category's version, for an update's optimistic check", example = "0")
        int version,

        @Schema(description = "The account the category posts to today") @Nullable
        Account currentAccount,

        @Schema(description = "An account that takes over on a later date") @Nullable
        Account laterAccount,

        @Schema(description = "Every change, oldest first") List<HistoryItem> history,

        @Schema(description = "True when this answers a replayed requestId")
        boolean replayed) {

    /** An account of the category and the date it applies from (ADR-0064: id with number and name). */
    @Schema(name = "PettyExpenseCategoryAccount", description = "An expense account of a petty-expense category")
    public record Account(
            @Schema(description = "GL account id") UUID glAccountId,

            @Schema(description = "Account number", example = "6295")
            String accountCode,

            @Schema(description = "Account name", example = "Staff Meals & Refreshments")
            String accountName,

            @Schema(description = "First day the account applies")
            LocalDate effectiveFrom) {}

    /** One change: when, who, what changed from what to what, and why. */
    @Schema(name = "PettyExpenseCategoryHistoryItem", description = "One change of a petty-expense category")
    public record HistoryItem(
            @Schema(description = "When the change was made")
            Instant changedAt,

            @Schema(description = "Who made it (from the security context)")
            String actor,

            @Schema(description = "What changed") PettyExpenseCategoryChangeType changeType,

            @Schema(description = "The value before") @Nullable
            String oldValue,

            @Schema(description = "The value after") String newValue,
            @Schema(description = "Why") String justification) {}

    /** The same view, marked as the answer to a replayed requestId. */
    public PettyExpenseCategoryResponse asReplay() {
        return new PettyExpenseCategoryResponse(
                code, label, examples, status, version, currentAccount, laterAccount, history, true);
    }
}
