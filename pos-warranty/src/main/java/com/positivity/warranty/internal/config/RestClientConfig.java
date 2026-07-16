package com.positivity.warranty.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient builders for pos-warranty outbound integrations (PRD §9.4).
 *
 * <p>The {@code @Primary} plain builder serves direct-URL callers (event-type and
 * permission registration against fixed base URLs); the {@code @LoadBalanced}
 * builder resolves Eureka service ids for the sibling-service clients in
 * {@code internal/client}.
 *
 * <p>Bounded connect/read timeouts prevent a hung sibling service from blocking a worker
 * thread (and any pooled DB connection or row lock held by the calling transaction)
 * indefinitely — mirrors the pos-invoice {@code RestClientConfig}.
 */
@Configuration
public class RestClientConfig {

    @Value("${pos.restclient.connect.timeout:2000}")
    private int connectTimeoutMs;

    @Value("${pos.restclient.read.timeout:5000}")
    private int readTimeoutMs;

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(timeoutFactory());
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder().requestFactory(timeoutFactory());
    }

    private SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
