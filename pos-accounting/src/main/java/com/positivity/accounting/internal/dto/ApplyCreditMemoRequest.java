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
 * Request to apply a Credit Memo to an invoice.
 * Sent from Accounting service to Invoice service.
 * 
 * @see <a href="https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue #131</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyCreditMemoRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("creditMemoId")
    @NotNull(message = "Credit Memo ID is required")
    @NonNull
    private UUID creditMemoId;

    @JsonProperty("totalAmount")
    @NotNull(message = "Total amount is required")
    @Positive(message = "Total amount must be positive")
    @NonNull
    private BigDecimal totalAmount;

    @JsonProperty("appliedBy")
    @NotNull(message = "Applied by user is required")
    @NonNull
    private String appliedBy;

    @JsonProperty("currency")
    private String currency;
}
