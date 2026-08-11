package com.positivity.supplier.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient builder for pos-supplier platform registrations (event types with
 * pos-event-receiver, permissions with pos-security-service) against fixed base URLs —
 * mirrors pos-warranty {@code RestClientConfig}. Vendor-facing outbound transport is a later
 * CAP-317 concern and does not run through this builder.
 *
 * <p>Bounded connect/read timeouts prevent a hung platform service from blocking a worker
 * thread indefinitely.
 */
@Configuration
public class RestClientConfig {

    @Value("${pos.restclient.connect.timeout:2000}")
    private int connectTimeoutMs;

    @Value("${pos.restclient.read.timeout:5000}")
    private int readTimeoutMs;

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }
}
