package com.positivity.accounting.internal.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider.GatewayPaymentStatus;
import com.stripe.model.Charge;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stripe gateway: payment_ref metadata, failure code mapping and charge search query (#2642)")
class StripePaymentGatewayMappingTest {

    private final StripePaymentGateway gateway = new StripePaymentGateway(
            "sk_test_dummy_key_for_unit_tests", "", 24, Duration.ofSeconds(5), Duration.ofSeconds(20));

    @Test
    @DisplayName("the idempotency key (the paymentRef) is stamped as metadata.payment_ref")
    void stampsPaymentRef() {
        GatewayPaymentRequest request = GatewayPaymentRequest.builder()
                .idempotencyKey("PAY-412")
                .amount(new BigDecimal("10.00"))
                .currency("USD")
                .paymentMethod(PaymentMethod.ACH)
                .vendorId("V-1")
                .paymentSource("src_1")
                .build();

        assertThat(gateway.buildMetadata(request))
                .containsEntry("payment_ref", "PAY-412")
                .containsEntry("vendor_id", "V-1");
    }

    @Test
    @DisplayName("a failed charge maps its failure_code, message and status")
    void mapsFailureCode() {
        Charge charge = new Charge();
        charge.setId("ch_1");
        charge.setPaid(false);
        charge.setStatus("failed");
        charge.setFailureCode("card_declined");
        charge.setFailureMessage("Your card was declined.");

        GatewayPaymentResponse response = gateway.toResponse(charge, gateway.mapChargeStatus(charge));

        assertThat(response.getTransactionId()).isEqualTo("ch_1");
        assertThat(response.getStatus()).isEqualTo(GatewayPaymentStatus.FAILED);
        assertThat(response.getFailureCode()).isEqualTo("card_declined");
        assertThat(response.getFailureReason()).isEqualTo("Your card was declined.");
    }

    @Test
    @DisplayName("a succeeded charge has no failure code")
    void succeededHasNoFailureCode() {
        Charge charge = new Charge();
        charge.setId("ch_2");
        charge.setPaid(true);
        charge.setStatus("succeeded");

        GatewayPaymentResponse response = gateway.toResponse(charge, gateway.mapChargeStatus(charge));

        assertThat(response.getStatus()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
        assertThat(response.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("the search query names the payment_ref metadata and escapes quotes and backslashes in the ref")
    void buildsSearchQuery() {
        assertThat(StripePaymentGateway.referenceQuery("PAY-412")).isEqualTo("metadata['payment_ref']:'PAY-412'");
        assertThat(StripePaymentGateway.referenceQuery("it's")).isEqualTo("metadata['payment_ref']:'it\\'s'");
        assertThat(StripePaymentGateway.referenceQuery("a\\b")).isEqualTo("metadata['payment_ref']:'a\\\\b'");
    }
}
