package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class DefaultBillingPortAdapter implements BillingPort {

    @Override
    public @NonNull PaymentReversalResult reversePayment(@NonNull UUID paymentId,
            @NonNull ReversePaymentCommand command) {
        return new PaymentReversalResult(true, "VOID", "Reversed by default adapter");
    }
}