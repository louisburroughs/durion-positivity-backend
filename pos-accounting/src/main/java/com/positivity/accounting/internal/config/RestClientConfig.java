package com.positivity.accounting.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for Spring RestClient.
 * Provides RestClient bean for cross-service HTTP calls.
 *
 * **Usage:**
 * - Injected into service classes for making REST calls
 * - Used by InvoiceServiceClient for Invoice service integration
 *
 * **Configuration:**
 * - Connect timeout: prevents indefinite hangs during connection establishment
 * - Read timeout: prevents indefinite hangs waiting for response data
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Configuration
public class RestClientConfig {

    /**
     * Create RestClient bean with configured timeouts.
     * Uses SimpleClientHttpRequestFactory to enforce connect and read timeouts,
     * preventing threads from hanging indefinitely on downstream service issues.
     *
     * @param timeoutMs timeout in milliseconds for both connect and read operations
     * @return configured RestClient instance with timeout protection
     */
    @Bean
    public RestClient restClient(
            @Value("${pos.invoice.service.timeout:5000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
