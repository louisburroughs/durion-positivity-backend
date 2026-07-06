package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.payment.NoOpPaymentGatewayProvider;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.payment.StripePaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PaymentGatewayConfig {

    @Bean
    @ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "stripe", matchIfMissing = true)
    @ConditionalOnExpression("!'${stripe.api-key:}'.trim().isEmpty()")
    StripePaymentGateway stripePaymentGateway(
            @Value("${stripe.api-key:}") String apiKey,
            @Value("${stripe.connect-account:}") String connectAccount,
            @Value("${stripe.idempotency-window-hours:24}") long idempotencyWindowHours) {
        return new StripePaymentGateway(apiKey, connectAccount, idempotencyWindowHours);
    }

    @Bean
    @ConditionalOnMissingBean(PaymentGatewayProvider.class)
    NoOpPaymentGatewayProvider noOpPaymentGatewayProvider() {
        return new NoOpPaymentGatewayProvider();
    }
}
