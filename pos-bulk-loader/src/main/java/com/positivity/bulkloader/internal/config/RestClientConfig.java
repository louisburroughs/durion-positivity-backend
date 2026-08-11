package com.positivity.bulkloader.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the {@link RestClient.Builder} beans injected by this module's outbound clients.
 *
 * <p>Spring Boot's auto-configured {@code RestClient.Builder} is absent without service
 * discovery (e.g. the {@code openapi} profile), so without this bean the context fails to
 * start with {@code UnsatisfiedDependencyException} on {@code RestClient$Builder}. The
 * {@code @Primary} plain builder serves direct-URL callers (startup registration in
 * {@code EventTypeInitializer} / {@code PermissionRegistration}); the {@code @LoadBalanced}
 * builder resolves Eureka service ids for the bulk-ingest writers in
 * {@code BatchConfiguration} (#641). Bounded connect/read timeouts keep a hung sibling from
 * blocking a worker thread.
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder(
            @Value("${pos.restclient.connect.timeout:2000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        return RestClient.builder().requestFactory(timeoutFactory(connectTimeoutMs, readTimeoutMs));
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(
            @Value("${pos.restclient.connect.timeout:2000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        return RestClient.builder().requestFactory(timeoutFactory(connectTimeoutMs, readTimeoutMs));
    }

    private SimpleClientHttpRequestFactory timeoutFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
