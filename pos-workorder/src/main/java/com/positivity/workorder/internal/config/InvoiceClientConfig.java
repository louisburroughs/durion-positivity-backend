package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the pos-invoice billing-rules lookup (CAP:092 Story #98), the one remaining
 * synchronous workorder→invoice read after ADR-0044 Phase 5.4 (#900) moved invoice generation to
 * {@code invoice.commands.v1} / {@code invoice.events.v1}. Retiring this read needs its own
 * billing-rules fact/replica and is out of #900's scope.
 */
@Configuration
public class InvoiceClientConfig {

    @Bean
    public RestClient invoiceServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.invoice.service-id:invoice}") String serviceId) {
        return restClientBuilder.baseUrl("http://" + serviceId).build();
    }
}
