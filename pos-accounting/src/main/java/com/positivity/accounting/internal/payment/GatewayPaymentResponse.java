package com.positivity.accounting.internal.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for payment gateway execution responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayPaymentResponse {

    @NotBlank(message = "transactionId is required")
    private String transactionId;

    @NotNull(message = "status is required")
    private PaymentGatewayProvider.GatewayPaymentStatus status;

    @Size(max = 255, message = "authorizationCode must be at most 255 characters")
    private String authorizationCode;

    @Size(max = 2000, message = "failureReason must be at most 2000 characters")
    private String failureReason;

    private String rawResponse;

    /**
     * The gateway's own code for a failed, declined or returned charge (Stripe {@code charge.failure_code}, an ACH
     * return code such as {@code R01}); the free-text message stays in {@code failureReason} / {@code rawResponse}.
     */
    @Size(max = 64, message = "failureCode must be at most 64 characters")
    private String failureCode;

    /** A response without a failure code (the shape before {@code failureCode} existed). */
    public GatewayPaymentResponse(
            String transactionId,
            PaymentGatewayProvider.GatewayPaymentStatus status,
            String authorizationCode,
            String failureReason,
            String rawResponse) {
        this(transactionId, status, authorizationCode, failureReason, rawResponse, null);
    }
}
