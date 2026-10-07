package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.BankOpeningItemType;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.tax.common.validation.IsoCurrencyCode;
import com.positivity.tax.common.validation.IsoCurrencyCodeValidator;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A bank account's opening balance at cutover (#2572, OI-10; Accounting Domain ruling 2026-10-07): the bank's
 * balance at the end of {@code asOfDate} and the checks and deposits still in transit then.
 */
@Schema(description = "A bank account's opening balance at cutover, with the items still in transit")
public record BankOpeningBalanceRequest(
        @Schema(
                description = "The cutover date: the end-of-day balance, usually the day before go-live. The entry is"
                        + " dated on it; it must not be after today and its period must be open",
                example = "2025-10-31",
                requiredMode = REQUIRED)
        @Nullable
        LocalDate asOfDate,

        @Schema(
                description = "The bank's balance at the end of asOfDate, in functional currency; negative when the"
                        + " account was overdrawn",
                example = "10000.00",
                requiredMode = REQUIRED)
        @Nullable
        BigDecimal statementBalance,

        @Schema(
                description = "The ISO 4217 code of statementBalance and every item amount (ADR-0067 R-1); it must be"
                        + " the bank account's currency, the functional currency",
                example = "USD",
                minLength = 3,
                maxLength = 3,
                requiredMode = REQUIRED)
        @IsoCurrencyCode
        @Nullable
        String currencyCode,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The checks written and deposits made on or before asOfDate that the"
                                        + " bank had not yet cleared; may be empty"))
        @Nullable
        List<OutstandingItem> outstandingItems,

        @Schema(
                description = "Why the opening is what it is (at least 10 characters); kept in the audit log",
                example = "Opening balance per the October bank statement",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                example = "019a0000-0000-7000-8000-000000000201",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId) {

    /** Longest reference accepted: the bank transaction reference it will be matched against holds 255. */
    public static final int MAX_REFERENCE = 255;

    /** An item in transit at cutover: it posts as its own bank line, carrying its reference and date. */
    @Schema(name = "BankOpeningBalanceOutstandingItem", description = "A check or deposit still in transit at cutover")
    public record OutstandingItem(
            @Schema(description = "What the item is", example = "OUTSTANDING_CHECK", requiredMode = REQUIRED) @Nullable
            BankOpeningItemType type,

            @Schema(
                    description = "The check number or deposit reference",
                    example = "1043",
                    maxLength = MAX_REFERENCE,
                    requiredMode = REQUIRED)
            @Nullable
            String reference,

            @Schema(
                    description = "The date the check was written or the deposit made; on or before asOfDate",
                    example = "2025-10-28",
                    requiredMode = REQUIRED)
            @Nullable
            LocalDate itemDate,

            @Schema(
                    description = "The item's amount, more than zero, in functional currency",
                    example = "450.00",
                    requiredMode = REQUIRED)
            @Nullable
            BigDecimal amount) {}

    /** The items, an absent list read as none. */
    public @NonNull List<OutstandingItem> items() {
        return outstandingItems == null ? List.of() : outstandingItems;
    }

    /**
     * Refuses a body the command cannot act on, naming the offending field in {@code fieldErrors}.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        if (asOfDate == null) {
            throw invalid("asOfDate", "asOfDate is required");
        }
        if (statementBalance == null) {
            throw invalid("statementBalance", "statementBalance is required");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw invalid("currencyCode", "currencyCode is required");
        }
        // ADR-0067 R-3: the ISO 4217 list behind @IsoCurrencyCode, never a pattern.
        if (!new IsoCurrencyCodeValidator().isValid(currencyCode, null)) {
            throw invalid("currencyCode", "currencyCode must be an ISO 4217 currency code");
        }
        List<OutstandingItem> items = items();
        for (int i = 0; i < items.size(); i++) {
            requireValid(items.get(i), "outstandingItems[" + i + "]");
        }
        try {
            CashRequests.requireJustification(justification, "justification");
        } catch (InvalidRequestParameterException e) {
            throw invalid("justification", e.getMessage());
        }
        if (requestId == null) {
            throw invalid("requestId", "requestId is required");
        }
    }

    private void requireValid(@Nullable OutstandingItem item, String field) {
        if (item == null) {
            throw invalid(field, field + " is required");
        }
        if (item.type() == null) {
            throw invalid(field + ".type", field + ".type is required");
        }
        if (item.reference() == null || item.reference().isBlank()) {
            throw invalid(field + ".reference", field + ".reference is required");
        }
        if (item.reference().trim().length() > MAX_REFERENCE) {
            throw invalid(field + ".reference", field + ".reference must not exceed " + MAX_REFERENCE + " characters");
        }
        if (item.itemDate() == null) {
            throw invalid(field + ".itemDate", field + ".itemDate is required");
        }
        if (item.itemDate().isAfter(asOfDate)) {
            throw invalid(field + ".itemDate", field + ".itemDate must be on or before asOfDate " + asOfDate);
        }
        if (item.amount() == null || item.amount().signum() <= 0) {
            throw invalid(field + ".amount", field + ".amount is required and must be more than zero");
        }
    }

    private static InvalidRequestParameterException invalid(String field, String message) {
        return InvalidRequestParameterException.forField(field, message);
    }
}
