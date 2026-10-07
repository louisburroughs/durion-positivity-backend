package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Reverse a bank deposit of drawer cash (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5; ADR-0047). */
@Schema(description = "Reverse a bank deposit; its sessions wait to be deposited again")
public record DepositReversalRequest(
        @Schema(
                description = "Why the deposit is reversed (at least 10 characters); kept with the deposit and the"
                        + " reversal entry",
                example = "Deposited into the wrong bank account",
                minLength = CashRequests.MIN_JUSTIFICATION,
                maxLength = DepositReversalRequest.MAX_REASON,
                requiredMode = REQUIRED)
        @Nullable
        String reason,

        @Schema(
                description = "The reversal entry's date; when omitted, the deposit's date if its period is open, else"
                        + " today",
                example = "2026-10-09",
                requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate reversalDate,

        @Schema(
                description = "Why the reversal posts into a closed period; honoured only with"
                        + " accounting:period:override",
                example = "Correction of a deposit in the closed month",
                maxLength = CashRequests.MAX_JUSTIFICATION,
                requiredMode = NOT_REQUIRED)
        @Nullable
        String overrideJustification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                example = "019a0000-0000-7000-8000-000000000302",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId) {

    /**
     * Longest reason: the reversal entry's description ({@code REVERSAL of <entry id> - Reason: <reason>}) holds at
     * most 500 characters.
     */
    public static final int MAX_REASON = 400;

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR} naming the field)
     */
    public void requireValid() {
        if (reason == null || reason.trim().length() < CashRequests.MIN_JUSTIFICATION) {
            throw InvalidRequestParameterException.forField(
                    "reason",
                    "reason is required and must be at least " + CashRequests.MIN_JUSTIFICATION + " characters");
        }
        if (reason.trim().length() > MAX_REASON) {
            throw InvalidRequestParameterException.forField(
                    "reason", "reason must not exceed " + MAX_REASON + " characters");
        }
        if (requestId == null) {
            throw InvalidRequestParameterException.forField("requestId", "requestId is required");
        }
        if (overrideJustification != null && overrideJustification.length() > CashRequests.MAX_JUSTIFICATION) {
            throw InvalidRequestParameterException.forField(
                    "overrideJustification",
                    "overrideJustification must not exceed " + CashRequests.MAX_JUSTIFICATION + " characters");
        }
    }
}
