package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for pos-tax service REST client.
 */
@Configuration
public class TaxClientConfig {

    @Bean
    public RestClient taxServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.tax.base-url:http://pos-tax:8091}") String taxServiceBaseUrl) {
        return restClientBuilder
                .baseUrl(taxServiceBaseUrl)
                .build();
    }
}
