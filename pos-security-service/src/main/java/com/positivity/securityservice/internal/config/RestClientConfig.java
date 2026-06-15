package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final String INTERNAL_USER = "pos-security-service";
    private static final String PEOPLE_AUTHORITIES =
            "people:person:create,people:person:delete,people:userLink:view,people:userLink:write";
    private static final String CUSTOMER_AUTHORITIES = "crm:person:read";

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient peopleRegistrationRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.people.service-id:people}") String serviceId,
            @Value("${pos.restclient.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.requestFactory(factory)
                .baseUrl("http://" + serviceId)
                .defaultHeader("X-User", INTERNAL_USER)
                .defaultHeader("X-Authorities", PEOPLE_AUTHORITIES)
                .build();
    }

    @Bean
    public RestClient customerRegistrationRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.customer.service-id:customer}") String serviceId,
            @Value("${pos.restclient.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.requestFactory(factory)
                .baseUrl("http://" + serviceId)
                .defaultHeader("X-User", INTERNAL_USER)
                .defaultHeader("X-Authorities", CUSTOMER_AUTHORITIES)
                .build();
    }
}
