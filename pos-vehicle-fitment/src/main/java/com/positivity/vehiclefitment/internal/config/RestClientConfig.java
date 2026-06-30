package com.positivity.vehiclefitment.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    /**
     * The module's {@code EventTypeInitializer} injects a {@link RestClient.Builder}, but
     * Spring Boot's auto-configured builder is absent without service discovery (e.g. the
     * {@code openapi} profile), so the context failed to start with
     * {@code UnsatisfiedDependencyException} on {@code RestClient$Builder}. Declare it
     * explicitly with bounded connect/read timeouts.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder(
            @Value("${pos.restclient.connect.timeout:2000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }
}
