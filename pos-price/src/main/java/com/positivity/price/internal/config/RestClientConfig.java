package com.positivity.price.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the {@link RestClient.Builder} bean injected by this module's outbound clients
 * (e.g. the event-type registration in {@code EventTypeInitializer}).
 *
 * <p>Spring Boot's auto-configured {@code RestClient.Builder} is absent without service
 * discovery (e.g. the {@code openapi} profile), so without this bean the context fails to
 * start with {@code UnsatisfiedDependencyException} on {@code RestClient$Builder}. The module
 * addresses siblings by direct DNS base-urls, so a plain builder is sufficient; bounded
 * connect/read timeouts keep a hung sibling from blocking a worker thread.
 */
@Configuration
public class RestClientConfig {

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
