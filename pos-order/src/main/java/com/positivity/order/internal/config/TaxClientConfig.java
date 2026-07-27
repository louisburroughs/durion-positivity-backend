package com.positivity.order.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-tax (ADR-0044 utility module — synchronous REST permitted). Same default
 * base URL as pos-workorder's tax client (pos-tax is internal-only on 8091).
 */
@Configuration
public class TaxClientConfig {

    @Bean
    public RestClient taxServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.tax.base-url:http://pos-tax:8091}") String taxServiceBaseUrl) {
        return restClientBuilder.baseUrl(taxServiceBaseUrl).build();
    }
}
