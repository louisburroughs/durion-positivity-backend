package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for pos-invoice service client.
 * Enables cross-service communication for billing rules lookup (CAP:092 Story
 * #98).
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
