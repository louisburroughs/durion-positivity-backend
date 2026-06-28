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
 * Request to apply a Credit Memo to an invoice.
 * Sent from Accounting service to Invoice service.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to apply a credit memo to an invoice")
public class ApplyCreditMemoRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(
            description = "Identifier of the credit memo to apply",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @JsonProperty("creditMemoId")
    @NotNull(message = "Credit Memo ID is required")
    @NonNull
    private UUID creditMemoId;

    @Schema(description = "Total amount of the credit memo to apply", example = "1250.00", requiredMode = REQUIRED)
    @JsonProperty("totalAmount")
    @NotNull(message = "Total amount is required")
    @Positive(message = "Total amount must be positive")
    @NonNull
    private BigDecimal totalAmount;

    @Schema(
            description = "Identifier of the user applying the credit memo",
            example = "ap_clerk_1",
            requiredMode = REQUIRED)
    @JsonProperty("appliedBy")
    @NotNull(message = "Applied by user is required")
    @NonNull
    private String appliedBy;

    @Schema(description = "Currency code of the credit memo (ISO 4217)", example = "USD", requiredMode = NOT_REQUIRED)
    @JsonProperty("currency")
    private String currency;
}
