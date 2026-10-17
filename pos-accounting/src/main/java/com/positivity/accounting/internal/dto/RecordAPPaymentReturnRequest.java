package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /v1/accounting/ap/payments/{paymentId}/record-return} (#2642, AW42): the bank or the processor returned
 * a charge that had moved money. The payment's entry is reversed on the return date (or the detection date when that
 * period is closed), its allocations reopen and the bills are payable again.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Record a returned AP payment charge: the mirror entry posts and the allocations reopen")
public class RecordAPPaymentReturnRequest {

    @NotNull(message = "requestId is required")
    @Schema(
            description = "The caller's idempotency key (UUID): a replay with the same body answers the payment;"
                    + " a different body is 409 IDEMPOTENCY_CONFLICT",
            example = "0199c2d4-8f1a-7b3c-9d4e-5f6a7b8c9d0e",
            requiredMode = REQUIRED)
    private UUID requestId;

    @Nullable
    @Schema(
            description = "The date the bank or processor returned the charge; today when omitted. Not after today."
                    + " The mirror entry is dated here when the period is open, else on the detection date",
            example = "2026-10-15",
            requiredMode = NOT_REQUIRED)
    private LocalDate returnDate;

    @Nullable
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_.:-]*$", message = "failureCode is a code, not a message")
    @Schema(
            description = "The gateway's or bank's return code (an ACH return code such as R01); never free text",
            example = "R01",
            requiredMode = NOT_REQUIRED)
    private String failureCode;

    @NotBlank(message = "justification is required")
    @Size(min = 10, max = 1000)
    @Schema(
            description = "Why the return is recorded: the statement line or the processor notice it rests on",
            example = "Bank statement 2026-10-15: ACH return R01 (insufficient funds) for PAY-2026-0412",
            requiredMode = REQUIRED)
    private String justification;

    @Nullable
    @Size(max = 1000)
    @Schema(
            description = "Closed-period override of the caller (accounting:period:override) when the detection"
                    + " date's period is CLOSED; may be omitted",
            requiredMode = NOT_REQUIRED)
    private String overrideJustification;
}
