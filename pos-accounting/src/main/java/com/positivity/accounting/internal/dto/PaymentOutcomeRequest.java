package com.positivity.accounting.internal.dto;

import java.util.UUID;
import com.positivity.accounting.internal.enums.PaymentOutcomeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOutcomeRequest {

    private UUID invoiceId;
    /**
     * Primary idempotency key — matches transactionId on the payment gateway
     * receipt.
     */
    private String transactionId;
    /** Fallback idempotency key when transactionId is absent. */
    private String idempotencyKey;
    private Long amountMinor;
    private String currency;
    private PaymentOutcomeType outcomeType;
    private UUID customerId;
}