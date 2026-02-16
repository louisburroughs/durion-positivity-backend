package com.positivity.workorder.internal.config;

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
public class InvoiceServiceClientConfig {

    @Bean
    public RestClient invoiceServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.invoice.base-url:http://pos-invoice:8089}") String invoiceBaseUrl) {
        return restClientBuilder
                .baseUrl(invoiceBaseUrl)
                .build();
    }
}
