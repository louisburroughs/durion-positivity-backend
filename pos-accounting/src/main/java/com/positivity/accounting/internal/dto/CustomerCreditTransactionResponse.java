package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of applying or refunding a customer credit (issue #992). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single customer-credit draw-down and the credit state it left behind")
public class CustomerCreditTransactionResponse {

    @Schema(description = "Draw-down identifier")
    private UUID creditTransactionId;

    @Schema(description = "Credit that was drawn down")
    private UUID creditId;

    @Schema(description = "Whether the credit settled an invoice or was refunded as cash")
    private CustomerCreditTransactionType transactionType;

    @Schema(description = "Invoice settled by an APPLICATION; null for a REFUND")
    private UUID invoiceId;

    @Schema(description = "Amount drawn down", example = "20.00")
    private BigDecimal amount;

    @Schema(description = "Currency (ISO 4217)", example = "USD")
    private String currency;

    @Schema(description = "Caller-supplied idempotency key this draw-down was recorded under")
    private String requestId;

    @Schema(description = "Credit's remaining open amount after this draw-down", example = "30.00")
    private BigDecimal creditOpenAmountAfter;

    @Schema(description = "Invoice balance after the credit was applied; null for a REFUND", example = "80.00")
    private BigDecimal invoiceBalanceAfter;

    @Schema(description = "When the draw-down was recorded")
    private Instant createdAt;

    @Schema(
            description =
                    "True when this response replays an already-processed request id rather than recording a new draw-down")
    private boolean replayed;
}
