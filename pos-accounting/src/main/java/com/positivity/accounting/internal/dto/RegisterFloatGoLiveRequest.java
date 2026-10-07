package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Establish a register's go-live float (#2511; SPEC-accounting-workspace §4.6 "Float", AW17). */
@Schema(description = "Establish a register's go-live change float against opening balance equity")
public record RegisterFloatGoLiveRequest(
        @Schema(
                description = "The location the register belongs to",
                example = "019a0000-0000-7000-8000-00000000a001",
                requiredMode = REQUIRED)
        @Nullable
        UUID locationId,

        @Schema(
                description = "The register's change float on the go-live date, in functional currency; more than zero",
                example = "200.00",
                requiredMode = REQUIRED)
        @Nullable
        BigDecimal amount,

        @Schema(
                description = "The go-live date the entry is dated on; it must fall in an open period",
                example = "2026-10-01",
                requiredMode = REQUIRED)
        @Nullable
        LocalDate goLiveDate,

        @Schema(
                description = "Why the float is what it is (at least 10 characters); kept in the history and the"
                        + " audit log",
                example = "Counted float in drawer 1 at go-live",
                minLength = 10,
                maxLength = 1000,
                requiredMode = REQUIRED)
        @Nullable
        String justification,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                example = "019a0000-0000-7000-8000-000000000101",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId) {

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR})
     */
    public void requireValid() {
        if (locationId == null) {
            throw new InvalidRequestParameterException("locationId is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestParameterException("amount is required and must be more than zero");
        }
        if (goLiveDate == null) {
            throw new InvalidRequestParameterException("goLiveDate is required");
        }
        CashRequests.requireJustification(justification, "justification");
        if (requestId == null) {
            throw new InvalidRequestParameterException("requestId is required");
        }
    }
}
