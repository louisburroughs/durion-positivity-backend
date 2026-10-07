package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.RegisterFloatRelocationReason;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Move a register, and its change float, to another location (#2571; SPEC-accounting-workspace §4.6 "Float",
 * AW32): to correct a register set up under the wrong location, or because the drawer physically moved.
 */
@Schema(description = "Move a register's change float from one location to another")
public record RegisterFloatRelocationRequest(
        @Schema(
                description = "The location the register is held at now",
                example = "019a0000-0000-7000-8000-00000000a001",
                requiredMode = REQUIRED)
        @Nullable
        UUID fromLocationId,

        @Schema(
                description = "The location the register moves to; differs from fromLocationId",
                example = "019a0000-0000-7000-8000-00000000a002",
                requiredMode = REQUIRED)
        @Nullable
        UUID toLocationId,

        @Schema(
                description = "Why the register moves: ENTERED_IN_ERROR (set up under the wrong location) or MOVED"
                        + " (the drawer physically moved); both post the same entry",
                example = "MOVED",
                allowableValues = {"ENTERED_IN_ERROR", "MOVED"},
                requiredMode = REQUIRED)
        @Nullable
        RegisterFloatRelocationReason reason,

        @Schema(
                description = "The date the move is posted on; today in the tenant's accounting time zone when omitted."
                        + " Not after today, and not before the register's latest float change",
                example = "2026-10-15",
                requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate effectiveDate,

        @Schema(
                description = "Why the register moves (at least 10 characters); kept in the history and the audit log",
                example = "Drawer 1 moved to the new shop",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                example = "019a0000-0000-7000-8000-000000000103",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId,

        @Schema(
                description = "Why the move posts into a closed period; honoured only with accounting:period:override",
                example = "The drawer moved on the last day of the closed month",
                maxLength = 1000,
                requiredMode = NOT_REQUIRED)
        @Nullable
        String overrideJustification) {

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        if (fromLocationId == null) {
            throw new InvalidRequestParameterException("fromLocationId is required");
        }
        if (toLocationId == null) {
            throw new InvalidRequestParameterException("toLocationId is required");
        }
        if (reason == null || reason == RegisterFloatRelocationReason.REVERSAL_FOLLOW_UP) {
            throw new InvalidRequestParameterException("reason is required and must be ENTERED_IN_ERROR or MOVED");
        }
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
        if (overrideJustification != null && overrideJustification.length() > CashRequests.MAX_JUSTIFICATION) {
            throw new InvalidRequestParameterException(
                    "overrideJustification must not exceed " + CashRequests.MAX_JUSTIFICATION + " characters");
        }
    }
}
