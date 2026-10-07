package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Change a register's float against a bank account (#2511; SPEC-accounting-workspace §4.6 "Float", AW16). */
@Schema(description = "Set a register's change float to a new amount, the difference moving to or from a bank account")
public record RegisterFloatChangeRequest(
        @Schema(
                description = "The location the register belongs to",
                example = "019a0000-0000-7000-8000-00000000a001",
                requiredMode = REQUIRED)
        @Nullable
        UUID locationId,

        @Schema(
                description = "The register's new float, in functional currency; zero or more",
                example = "300.00",
                requiredMode = REQUIRED)
        @Nullable
        BigDecimal amount,

        @Schema(
                description = "The active BANK_CASH account in functional currency the difference moves to or from",
                example = "019a0000-0000-7000-8000-00000000b000",
                requiredMode = REQUIRED)
        @Nullable
        UUID bankGlAccountId,

        @Schema(
                description = "The date the change is posted on; today in the tenant's accounting time zone when"
                        + " omitted",
                example = "2026-10-15",
                requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate effectiveDate,

        @Schema(
                description = "Why the float changes (at least 10 characters); kept in the history and the audit log",
                example = "More change needed for the weekend rush",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                example = "019a0000-0000-7000-8000-000000000102",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId,

        @Schema(
                description =
                        "Why the change posts into a closed period; honoured only with" + " accounting:period:override",
                example = "Float counted on the last day of the closed month",
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
        if (locationId == null) {
            throw new InvalidRequestParameterException("locationId is required");
        }
        if (amount == null || amount.signum() < 0) {
            throw new InvalidRequestParameterException("amount is required and must not be negative");
        }
        if (bankGlAccountId == null) {
            throw new InvalidRequestParameterException("bankGlAccountId is required");
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
