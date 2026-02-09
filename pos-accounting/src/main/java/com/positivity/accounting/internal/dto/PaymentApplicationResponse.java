package com.positivity.accounting.internal.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.positivity.accounting.internal.enums.InvoiceStatus;

/**
 * Response DTO for payment application operations.
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PaymentApplicationResponse {

    private UUID paymentId;
    private UUID customerId;
    private String currency;
    private BigDecimal totalAmount;
    private BigDecimal appliedAmount;
    private BigDecimal remainingAmount;
    private List<ApplicationDetail> applications;
    private CustomerCreditInfo customerCredit;
    private Instant applicationTimestamp;
    private String applicationRequestId;

    /**
     * Details of a single invoice application.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    public static class ApplicationDetail {
        private UUID paymentApplicationId;
        private UUID invoiceId;
        private BigDecimal appliedAmount;
        private BigDecimal invoiceBalanceBefore;
        private BigDecimal invoiceBalanceAfter;
        private InvoiceStatus invoiceStatus;
    }

    /**
     * Customer credit created from overpayment.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    public static class CustomerCreditInfo {
        private UUID creditId;
        private BigDecimal amount;
        private Instant createdAt;
    }
}
