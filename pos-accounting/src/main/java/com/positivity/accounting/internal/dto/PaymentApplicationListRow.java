package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One A/R payment-application row for the applied-date-window list (Wave 2 E10, issue #1598).
 *
 * @see com.positivity.accounting.internal.service.PaymentApplicationQueryService#listByAppliedDateWindow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One pos-accounting cash application of a customer payment to an invoice")
public class PaymentApplicationListRow {

    @Schema(
            description = "Payment application identifier",
            example = "01936e5f-1234-7a3d-8b6e-3c4567890124",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("applicationId")
    private UUID applicationId;

    @Schema(
            description = "Receivable payment identifier this application belongs to",
            example = "01936e60-1234-7a3d-8b6e-3c4567890125",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("paymentId")
    private UUID paymentId;

    @Schema(
            description = "Invoice identifier this application was made against",
            example = "01936e61-1234-7a3d-8b6e-3c4567890126",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("invoiceId")
    private UUID invoiceId;

    @Schema(
            description = "Timestamp the application was recorded (application_timestamp)",
            example = "2026-06-15T14:32:00Z",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("appliedAt")
    private Instant appliedAt;

    @Schema(description = "Amount applied to the invoice", example = "450.00", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("amount")
    private BigDecimal amount;

    @Schema(
            description = "True when this application has since been reversed (PaymentApplicationReversal exists)."
                    + " Always false when the caller did not set includeReversed=true, since reversed"
                    + " applications are excluded from the list entirely by default.",
            example = "false",
            requiredMode = REQUIRED)
    private boolean reversed;
}
