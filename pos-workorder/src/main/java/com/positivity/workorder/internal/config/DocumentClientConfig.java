package com.positivity.workorder.internal.config;

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
            RestClient.Builder restClientBuilder,
            @Value("${pos.documents.base-url:http://pos-documents:8080}") String documentServiceBaseUrl) {
        return restClientBuilder
                .baseUrl(documentServiceBaseUrl)
                .build();
    }
}
