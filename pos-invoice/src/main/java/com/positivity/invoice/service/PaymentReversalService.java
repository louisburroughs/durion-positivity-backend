package com.positivity.invoice.service;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.VoidReason;
import java.math.BigDecimal;
import java.util.List;
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

    /**
     * All refund records anchored to the invoice — payment-intent refunds whose intent belongs
     * to the invoice and standalone invoice-anchored refunds alike — ordered by requestedAt.
     * Serves pos-warranty settlement reconciliation (#922).
     *
     * @throws com.positivity.invoice.internal.exception.InvoiceNotFoundException if the
     *         invoice does not exist
     */
    @NonNull
    List<RefundPaymentResult> listRefundsForInvoice(@NonNull UUID invoiceId);
}
