package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Request to reverse payment application on invoice (sent to Invoice service).
 * Restores invoice balance and updates status after payment reversal in
 * accounting.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to reverse a payment application on an invoice")
public class ReversePaymentApplicationRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Original payment application ID being reversed
     */
    @Schema(
            description = "Original payment application identifier being reversed",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @JsonProperty("paymentApplicationId")
    @NotNull(message = "Payment application ID is required")
    @NonNull
    private UUID paymentApplicationId;

    /**
     * Reversal record ID (new compensating transaction).
     * Optional for compensating reversals where no local reversal record is created.
     * When null, Invoice service should use paymentApplicationId as idempotency key.
     */
    @Schema(
            description = "Reversal record identifier; when null, paymentApplicationId is used as idempotency key",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("reversalId")
    private UUID reversalId;

    /**
     * Amount to restore to invoice
     */
    @Schema(description = "Amount to restore to the invoice balance", example = "1250.00", requiredMode = REQUIRED)
    @JsonProperty("amountToRestore")
    @NotNull(message = "Amount to restore is required")
    @Positive(message = "Amount to restore must be greater than 0")
    @NonNull
    private BigDecimal amountToRestore;

    /**
     * Reason for reversal (required for audit trail)
     */
    @Schema(
            description = "Reason for the reversal (required for audit trail)",
            example = "Duplicate payment applied in error",
            requiredMode = REQUIRED)
    @JsonProperty("reason")
    @NotNull(message = "Reason is required")
    @NonNull
    private String reason;

    /**
     * User performing reversal
     */
    @Schema(
            description = "Identifier of the user performing the reversal",
            example = "ap.manager",
            requiredMode = REQUIRED)
    @JsonProperty("reversedBy")
    @NotNull(message = "Reversed by user is required")
    @NonNull
    private String reversedBy;
}
