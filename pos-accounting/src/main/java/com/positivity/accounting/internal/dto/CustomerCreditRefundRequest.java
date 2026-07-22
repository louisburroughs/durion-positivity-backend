package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to refund an open customer credit back to the customer (issue #992). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Refund an open customer credit as cash back to the customer")
public class CustomerCreditRefundRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(
            description =
                    "Caller-supplied idempotency key. Replaying the same key returns the original refund instead of relieving the credit liability twice",
            example = "credit-refund-2026-07-22-0001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @NotNull
    @DecimalMin(value = "0.01", message = "Refund amount must be positive")
    @Digits(integer = 15, fraction = 4)
    @Schema(
            description = "Amount to refund. Must not exceed the credit's open amount",
            example = "50.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @Size(max = 500)
    @Schema(description = "Optional audit note explaining why the credit is being refunded")
    private String note;
}
