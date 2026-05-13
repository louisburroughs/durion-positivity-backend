package com.positivity.workorder.internal.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for pos-people service REST client.
 * Used for resolving the current user's primary location assignment.
 */
@Configuration
public class PeopleClientConfig {

    @Bean
    public RestClient peopleServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.people.base-url:http://api-gateway}") String peopleServiceBaseUrl) {
        return restClientBuilder.baseUrl(peopleServiceBaseUrl).build();
    }
}
