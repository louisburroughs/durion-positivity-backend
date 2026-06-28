package com.positivity.invoice.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
