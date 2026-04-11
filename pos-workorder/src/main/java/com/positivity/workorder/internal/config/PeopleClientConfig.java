package com.positivity.workorder.internal.config;

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
            RestClient.Builder restClientBuilder,
            @Value("${pos.people.base-url:http://pos-people:8084}") String peopleServiceBaseUrl) {
        return restClientBuilder
                .baseUrl(peopleServiceBaseUrl)
                .build();
    }
}
