package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final String INTERNAL_USER = "pos-security-service";
    private static final String PEOPLE_AUTHORITIES = "people:person:create,people:userLink:view,people:userLink:write";
    private static final String CUSTOMER_AUTHORITIES = "crm:person:read";

    @Bean
    public RestClient peopleRegistrationRestClient(
            RestClient.Builder builder,
            @Value("${pos.people.base-url:http://pos-people:8084}") String peopleBaseUrl,
            @Value("${pos.restclient.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.requestFactory(factory)
                .baseUrl(peopleBaseUrl)
                .defaultHeader("X-User", INTERNAL_USER)
                .defaultHeader("X-Authorities", PEOPLE_AUTHORITIES)
                .build();
    }

    @Bean
    public RestClient customerRegistrationRestClient(
            RestClient.Builder builder,
            @Value("${pos.customer.base-url:http://pos-customer:8084}") String customerBaseUrl,
            @Value("${pos.restclient.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.requestFactory(factory)
                .baseUrl(customerBaseUrl)
                .defaultHeader("X-User", INTERNAL_USER)
                .defaultHeader("X-Authorities", CUSTOMER_AUTHORITIES)
                .build();
    }
}
