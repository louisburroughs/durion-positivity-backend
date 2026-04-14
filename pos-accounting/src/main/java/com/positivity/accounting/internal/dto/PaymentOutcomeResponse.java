package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Outcome of payment processing against an invoice")
public class PaymentOutcomeResponse {

    @Schema(description = "Current invoice payment status after processing", example = "PAID")
    private PaymentStatus invoiceStatus;

    @Schema(description = "Total amount paid in minor units", example = "125000")
    private Long paidAmountMinor;

    @Schema(description = "Outstanding amount in minor units", example = "0")
    private Long outstandingAmountMinor;

    @Schema(description = "Whether this request was a detected duplicate", example = "false")
    private boolean duplicate;

    @Schema(description = "Whether downstream posting encountered an error", example = "false")
    private boolean postingError;
}
