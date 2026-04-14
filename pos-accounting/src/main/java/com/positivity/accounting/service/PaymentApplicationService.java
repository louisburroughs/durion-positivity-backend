package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PaymentApplicationService {

    @NonNull
    ReceivablePayment handlePaymentCleared(
            @NonNull UUID paymentId,
            @NonNull UUID customerId,
            @NonNull String currency,
            @NonNull BigDecimal totalAmount,
            @NonNull Instant clearedAt,
            @NonNull UUID sourceEventId);

    @NonNull
    PaymentApplicationResponse applyPaymentToInvoices(
            @NonNull UUID paymentId, @NonNull PaymentApplicationRequest request);

    void voidPayment(@NonNull UUID paymentId);

    void reversePayment(@NonNull UUID paymentId, @NonNull String reason);

    @NonNull
    PaymentApplicationReversal reversePaymentApplication(@NonNull UUID paymentApplicationId, @NonNull String reason);
}
