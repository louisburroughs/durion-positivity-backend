package com.positivity.order.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-invoice. pos-invoice is a domain module — this synchronous edge exists under
 * the scoped ADR-0044 amendment for pos-order (checkout invoice creation and saga payment
 * reversal, both money-moving counter-flows that must fail loudly in the request path; see
 * {@code docs/adr-0044-event-only-domain-walls.md} and pos-archunit {@code DomainWallsTest}).
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
