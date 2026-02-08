package com.positivity.accounting.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for Spring RestClient.
 * Provides RestClient bean for cross-service HTTP calls.
 *
 * **Usage:**
 * - Injected into service classes for making REST calls
 * - Used by InvoiceServiceClient for Invoice service integration
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Configuration
public class RestClientConfig {

    /**
     * Create default RestClient bean.
     * Can be customized further with timeout, interceptors, etc.
     *
     * @return configured RestClient instance
     */
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
