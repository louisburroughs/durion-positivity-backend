package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.payment.GatewayPaymentRequest;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Test-only payment gateway configuration.
 *
 * Prevents outbound Stripe calls during integration/contract tests and returns
 * deterministic successful responses.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("test")
public class TestPaymentGatewayConfig {

    @Bean
    @Primary
    public PaymentGatewayProvider testPaymentGatewayProvider() {
        return new InMemoryTestPaymentGatewayProvider();
    }

    private static final class InMemoryTestPaymentGatewayProvider implements PaymentGatewayProvider {
        private final Map<String, GatewayPaymentResponse> responsesByTransactionId = new ConcurrentHashMap<>();

        @Override
        public GatewayPaymentResponse executePayment(GatewayPaymentRequest request) {
            String transactionId = "test-gateway-" + request.getIdempotencyKey();
            GatewayPaymentResponse response = new GatewayPaymentResponse(
                    transactionId,
                    GatewayPaymentStatus.SUCCEEDED,
                    transactionId,
                    null,
                    "{\"provider\":\"test\",\"status\":\"SUCCEEDED\"}");
            responsesByTransactionId.put(transactionId, response);
            return response;
        }

        @Override
        public Optional<GatewayPaymentResponse> getPaymentStatus(String transactionId) {
            return Optional.ofNullable(responsesByTransactionId.get(transactionId));
        }

        @Override
        public String getProviderName() {
            return "TestGateway";
        }
    }
}
