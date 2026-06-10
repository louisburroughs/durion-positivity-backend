package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for pos-documents service REST client.
 */
@Configuration
public class DocumentClientConfig {

    @Bean
    public RestClient documentServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.documents.service-id:documents}") String serviceId) {
        return restClientBuilder.baseUrl("http://" + serviceId).build();
    }
}
