package com.positivity.accounting.internal.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.payment.PaymentGatewayProvider.GatewayPaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Local payment gateway: deterministic outcomes from the paymentRef suffix (#2642)")
class LocalPaymentGatewayTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static final class MutableClock extends Clock {
        private Instant now = T0;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LocalPaymentGateway gateway = new LocalPaymentGateway(clock, Duration.ofMinutes(1));

    private GatewayPaymentResponse pay(String ref) throws Exception {
        GatewayPaymentRequest request = new GatewayPaymentRequest();
        request.setIdempotencyKey(ref);
        return gateway.executePayment(request);
    }

    @Test
    @DisplayName("a plain ref succeeds with a local_<ref>_<epochMillis> id, and a re-read agrees")
    void plainRefSucceeds() throws Exception {
        GatewayPaymentResponse paid = pay("PAY-1");
        assertThat(paid.getStatus()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        assertThat(paid.getTransactionId()).isEqualTo("local_PAY-1_" + T0.toEpochMilli());
        assertThat(paid.getFailureCode()).isNull();
        clock.now = T0.plus(Duration.ofHours(1));
        assertThat(gateway.getPaymentStatus(paid.getTransactionId()))
                .get()
                .extracting(GatewayPaymentResponse::getStatus)
                .isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        assertThat(gateway.getProviderName()).isEqualTo("local");
    }

    @Test
    @DisplayName("-PENDING stays PENDING until settle-after has elapsed since the charge, then SUCCEEDED")
    void pendingSettles() throws Exception {
        GatewayPaymentResponse paid = pay("PAY-2-PENDING");
        assertThat(paid.getStatus()).isEqualTo(GatewayPaymentStatus.PENDING);
        clock.now = T0.plusSeconds(59);
        assertThat(gateway.getPaymentStatus(paid.getTransactionId()).orElseThrow().getStatus())
                .isEqualTo(GatewayPaymentStatus.PENDING);
        clock.now = T0.plusSeconds(60);
        assertThat(gateway.getPaymentStatus(paid.getTransactionId()).orElseThrow().getStatus())
                .isEqualTo(GatewayPaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("-DECLINE is DECLINED with LOCAL_DECLINE, on the charge and on every read")
    void declines() throws Exception {
        GatewayPaymentResponse paid = pay("PAY-3-DECLINE");
        assertThat(paid.getStatus()).isEqualTo(GatewayPaymentStatus.DECLINED);
        assertThat(paid.getFailureCode()).isEqualTo("LOCAL_DECLINE");
        clock.now = T0.plus(Duration.ofDays(1));
        GatewayPaymentResponse read = gateway.getPaymentStatus(paid.getTransactionId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(GatewayPaymentStatus.DECLINED);
        assertThat(read.getFailureCode()).isEqualTo("LOCAL_DECLINE");
    }

    @Test
    @DisplayName("-RETURN succeeds on the charge and is FAILED with LOCAL_RETURN on a re-read after settle-after")
    void returns() throws Exception {
        GatewayPaymentResponse paid = pay("PAY-4-RETURN");
        assertThat(paid.getStatus()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        clock.now = T0.plusSeconds(30);
        assertThat(gateway.getPaymentStatus(paid.getTransactionId()).orElseThrow().getStatus())
                .isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        clock.now = T0.plusSeconds(61);
        GatewayPaymentResponse read = gateway.getPaymentStatus(paid.getTransactionId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(GatewayPaymentStatus.FAILED);
        assertThat(read.getFailureCode()).isEqualTo("LOCAL_RETURN");
    }

    @Test
    @DisplayName("findByReference answers the settled outcome of a ref; an ref with a local_ prefix is the same ref")
    void findsByReference() throws Exception {
        assertThat(gateway.findByReference("PAY-5").orElseThrow().getStatus()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        assertThat(gateway.findByReference("PAY-5-PENDING").orElseThrow().getStatus())
                .isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        assertThat(gateway.findByReference("local_PAY-5-RETURN").orElseThrow().getFailureCode())
                .isEqualTo("LOCAL_RETURN");
        GatewayPaymentResponse declined = gateway.findByReference("PAY-5-DECLINE").orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(GatewayPaymentStatus.DECLINED);
        assertThat(declined.getTransactionId()).isEqualTo("local_PAY-5-DECLINE");
        assertThat(gateway.findByReference(" ")).isEmpty();
    }

    @Test
    @DisplayName("an id the adapter did not mint is unknown")
    void foreignIdIsEmpty() throws Exception {
        assertThat(gateway.getPaymentStatus("ch_123")).isEmpty();
        assertThat(gateway.getPaymentStatus("local_")).isEmpty();
    }

    @Test
    @DisplayName("a negative settle-after is refused at startup")
    void negativeSettleAfterRefused() {
        assertThatThrownBy(() -> new LocalPaymentGateway(clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.gateway.local.settle-after");
    }
}
