package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response from applying a Credit Memo to an invoice.
 * Returned by Invoice service to Accounting service.
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyCreditMemoResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("invoiceId")
    @NonNull
    private UUID invoiceId;

    @JsonProperty("balanceBefore")
    @NonNull
    private BigDecimal balanceBefore;

    @JsonProperty("balanceAfter")
    @NonNull
    private BigDecimal balanceAfter;

    @JsonProperty("status")
    @NonNull
    private String status;

    @JsonProperty("creditMemoApplied")
    @NonNull
    private BigDecimal creditMemoApplied;
}
