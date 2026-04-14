package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public class ApplyPaymentToInvoiceRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * ID of PaymentApplication record (for audit trail)
     */
    @JsonProperty("paymentApplicationId")
    @NotNull(message = "Payment application ID is required")
    @NonNull
    private UUID paymentApplicationId;

    /**
     * Amount being applied to this invoice
     */
    @JsonProperty("amountApplied")
    @NotNull(message = "Amount applied is required")
    @Positive(message = "Amount applied must be greater than 0")
    @NonNull
    private BigDecimal amountApplied;

    /**
     * Timestamp when payment was applied
     */
    @JsonProperty("appliedAt")
    @NotNull(message = "Applied timestamp is required")
    @NonNull
    private Instant appliedAt;

    /**
     * Currency of payment (must match invoice currency)
     */
    @JsonProperty("currency")
    @NotNull(message = "Currency is required")
    @NonNull
    private String currency;

    /**
     * Payment ID for reference
     */
    @JsonProperty("paymentId")
    private UUID paymentId;

    /**
     * User applying the payment (from audit context)
     */
    @JsonProperty("appliedBy")
    private String appliedBy;
}
