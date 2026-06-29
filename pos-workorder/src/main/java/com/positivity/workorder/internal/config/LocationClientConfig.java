package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for the pos-location service REST client used to resolve a
 * shop-location address for tax jurisdiction determination.
 */
@Configuration
public class LocationClientConfig {

    @Bean
    public RestClient locationServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.location.base-url:http://pos-location:8080}") String locationServiceBaseUrl) {
        return restClientBuilder.baseUrl(locationServiceBaseUrl).build();
    }
}
