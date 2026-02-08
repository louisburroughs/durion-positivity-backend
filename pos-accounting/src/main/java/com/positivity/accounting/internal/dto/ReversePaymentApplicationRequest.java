package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

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
public class ReversePaymentApplicationRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Original payment application ID being reversed
     */
    @JsonProperty("paymentApplicationId")
    @NotNull(message = "Payment application ID is required")
    @NonNull
    private UUID paymentApplicationId;

    /**
     * Reversal record ID (new compensating transaction)
     */
    @JsonProperty("reversalId")
    @NotNull(message = "Reversal ID is required")
    @NonNull
    private UUID reversalId;

    /**
     * Amount to restore to invoice
     */
    @JsonProperty("amountToRestore")
    @NotNull(message = "Amount to restore is required")
    @Positive(message = "Amount to restore must be greater than 0")
    @NonNull
    private BigDecimal amountToRestore;

    /**
     * Reason for reversal (required for audit trail)
     */
    @JsonProperty("reason")
    @NotNull(message = "Reason is required")
    @NonNull
    private String reason;

    /**
     * User performing reversal
     */
    @JsonProperty("reversedBy")
    @NotNull(message = "Reversed by user is required")
    @NonNull
    private String reversedBy;
}
