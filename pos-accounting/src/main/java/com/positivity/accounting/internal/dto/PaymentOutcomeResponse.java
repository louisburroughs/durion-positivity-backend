package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOutcomeResponse {

    private PaymentStatus invoiceStatus;
    private Long paidAmountMinor;
    private Long outstandingAmountMinor;
    private boolean duplicate;
    private boolean postingError;
}