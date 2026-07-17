package com.positivity.invoice.service;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.VoidReason;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PaymentReversalService {

    void voidPayment(
            @NonNull UUID invoiceId, @NonNull UUID paymentIntentId, @NonNull VoidReason reason, @Nullable String notes);

    @NonNull
    RefundPaymentResult refundPayment(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes,
            @Nullable String externalReference);

    /**
     * Records a standalone refund anchored to an invoice whose original payment is not in the
     * system (predecessor-system sale, pre-deploy invoice, vendor-paid). Disbursement happens
     * out of band; the record carries the refund liability (#926).
     */
    @NonNull
    RefundPaymentResult refundInvoiceStandalone(
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes,
            @Nullable String externalReference);

    /**
     * Records a standalone refund anchored to a customer party when no invoice exists in the
     * system. Disbursement happens out of band; the record carries the refund liability (#926).
     */
    @NonNull
    RefundPaymentResult refundPartyStandalone(
            @NonNull String partyId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes,
            @Nullable String externalReference);
}
