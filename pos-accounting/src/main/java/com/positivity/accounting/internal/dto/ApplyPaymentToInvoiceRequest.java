package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Request to apply payment to invoice (sent to Invoice service).
 * Updates invoice balance and status after payment application in accounting.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to apply a payment to an invoice")
public class ApplyPaymentToInvoiceRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * ID of PaymentApplication record (for audit trail)
     */
    @Schema(
            description = "Identifier of the payment application record (for audit trail)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @JsonProperty("paymentApplicationId")
    @NotNull(message = "Payment application ID is required")
    @NonNull
    private UUID paymentApplicationId;

    /**
     * Amount being applied to this invoice
     */
    @Schema(description = "Amount being applied to this invoice", example = "1250.00", requiredMode = REQUIRED)
    @JsonProperty("amountApplied")
    @NotNull(message = "Amount applied is required")
    @Positive(message = "Amount applied must be greater than 0")
    @NonNull
    private BigDecimal amountApplied;

    /**
     * Timestamp when payment was applied
     */
    @Schema(
            description = "Timestamp when the payment was applied (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @JsonProperty("appliedAt")
    @NotNull(message = "Applied timestamp is required")
    @NonNull
    private Instant appliedAt;

    /**
     * Currency of payment (must match invoice currency)
     */
    @Schema(description = "Currency of the payment (ISO 4217, must match invoice currency)", example = "USD", requiredMode = REQUIRED)
    @JsonProperty("currency")
    @NotNull(message = "Currency is required")
    @NonNull
    private String currency;

    /**
     * Payment ID for reference
     */
    @Schema(
            description = "Identifier of the payment for reference",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("paymentId")
    private UUID paymentId;

    /**
     * User applying the payment (from audit context)
     */
    @Schema(description = "Identifier of the user applying the payment", example = "ap_clerk_1", requiredMode = NOT_REQUIRED)
    @JsonProperty("appliedBy")
    private String appliedBy;
}
