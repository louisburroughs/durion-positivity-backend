package com.positivity.accounting.internal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.net.RequestOptions;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gateway's bounded waits (CAP:550 S42, #2627, AC12): the configured connect and read timeouts reach every call's
 * {@link RequestOptions}, beside its idempotency key.
 */
@DisplayName("Stripe gateway: connect and read timeouts on every call (#2627)")
class StripePaymentGatewayTimeoutTest {

    private static StripePaymentGateway gateway(Duration connect, Duration read) {
        return new StripePaymentGateway("sk_test_dummy_key_for_unit_tests", "", 24, connect, read);
    }

    @Test
    @DisplayName("AC12: the configured timeouts reach RequestOptions with the paymentRef as idempotency key")
    void configuredTimeoutsReachTheRequest() {
        RequestOptions options =
                gateway(Duration.ofSeconds(5), Duration.ofSeconds(20)).requestOptions("PAY-412");

        assertThat(options.getConnectTimeout()).isEqualTo(5_000);
        assertThat(options.getReadTimeout()).isEqualTo(20_000);
        assertThat(options.getIdempotencyKey()).isEqualTo("PAY-412");

        RequestOptions tighter =
                gateway(Duration.ofMillis(1_500), Duration.ofSeconds(8)).requestOptions("PAY-413");
        assertThat(tighter.getConnectTimeout()).isEqualTo(1_500);
        assertThat(tighter.getReadTimeout()).isEqualTo(8_000);

        RequestOptions read =
                gateway(Duration.ofSeconds(5), Duration.ofSeconds(20)).requestOptions(null);
        assertThat(read.getConnectTimeout()).as("a status read is bounded too").isEqualTo(5_000);
        assertThat(read.getReadTimeout()).isEqualTo(20_000);
        assertThat(read.getIdempotencyKey()).isNull();
    }

    @Test
    @DisplayName("a zero or negative timeout is refused at startup")
    void nonPositiveTimeoutIsRefused() {
        assertThatThrownBy(() -> gateway(Duration.ZERO, Duration.ofSeconds(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.gateway.connect-timeout");
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.gateway.read-timeout");
    }
}
