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
}
