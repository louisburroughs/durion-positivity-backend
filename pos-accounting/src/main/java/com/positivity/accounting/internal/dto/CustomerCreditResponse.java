package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A customer credit and its consumption state (issue #992). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AR customer credit with its remaining open amount")
public class CustomerCreditResponse {

    @Schema(description = "Credit identifier")
    private UUID creditId;

    @Schema(description = "Customer the credit belongs to")
    private UUID customerId;

    @Schema(description = "Currency (ISO 4217)", example = "USD")
    private String currency;

    @Schema(description = "Amount originally issued", example = "50.00")
    private BigDecimal amount;

    @Schema(description = "Cumulative amount applied against invoices", example = "20.00")
    private BigDecimal appliedAmount;

    @Schema(description = "Cumulative amount refunded to the customer", example = "0.00")
    private BigDecimal refundedAmount;

    @Schema(
            description =
                    "Remaining credit owed to the customer (amount - applied - refunded); this is the credit's contribution to the Customer Credit Liability (2300) control account",
            example = "30.00")
    private BigDecimal openAmount;

    @Schema(description = "Consumption state derived from the applied/refunded totals")
    private CustomerCreditStatus status;

    @Schema(description = "Payment whose overpayment created the credit")
    private UUID sourcePaymentId;

    @Schema(description = "When the credit was issued")
    private Instant createdAt;
}
