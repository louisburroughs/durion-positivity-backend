package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface BillingPort {
    @NonNull
    PaymentReversalResult reversePayment(@NonNull UUID paymentId, @NonNull ReversePaymentCommand command);
}
