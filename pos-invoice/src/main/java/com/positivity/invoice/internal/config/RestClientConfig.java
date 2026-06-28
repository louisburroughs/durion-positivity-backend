package com.positivity.invoice.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the {@link RestClient.Builder} bean used by the module's outbound clients
 * (tax, location, document-render, CRM and workorder references).
 *
 * <p>pos-invoice addresses sibling services by direct DNS base-urls (e.g.
 * {@code http://pos-customer:8080}) rather than service-discovery virtual hostnames, so a
 * plain builder is sufficient. Declaring it explicitly keeps the clients injectable in boots
 * where the auto-configured builder is not present (e.g. the {@code openapi}/{@code dev}
 * profile with service discovery disabled).
 *
 * <p>Bounded connect/read timeouts prevent a hung sibling service from blocking a worker
 * thread (and any pooled DB connection) indefinitely.
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
