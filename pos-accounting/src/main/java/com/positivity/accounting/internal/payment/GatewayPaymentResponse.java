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
}
