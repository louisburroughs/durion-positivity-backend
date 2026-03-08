package com.positivity.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.client.DefaultBillingPortAdapter;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultBillingPortAdapter} stub adapter — Issue #19.
 */
@DisplayName("DefaultBillingPortAdapter Unit Tests")
class DefaultBillingPortAdapterTest {

    private final DefaultBillingPortAdapter adapter = new DefaultBillingPortAdapter();

    @Test
    @DisplayName("reversePayment returns success=true with VOID type and default message")
    void reversePayment_returnsSuccessVoid() {
        UUID paymentId = UUID.randomUUID();
        ReversePaymentCommand command = new ReversePaymentCommand(
                "VOID",
                "USD",
                "ORDER_CANCELLED",
                UUID.randomUUID(),
                UUID.randomUUID().toString());

        PaymentReversalResult result = adapter.reversePayment(paymentId, command);

        assertThat(result.success()).isTrue();
        assertThat(result.paymentReversalType()).isEqualTo("VOID");
        assertThat(result.resultMessage()).isEqualTo("Reversed by default adapter");
    }
}
