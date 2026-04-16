package com.positivity.invoice.internal.config;

import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.payment.UnavailablePaymentGatewayAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaymentGatewayPort.class)
    public PaymentGatewayPort paymentGatewayPort() {
        return new UnavailablePaymentGatewayAdapter();
    }
}
