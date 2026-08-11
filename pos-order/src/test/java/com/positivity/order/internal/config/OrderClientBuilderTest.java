package com.positivity.order.internal.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Wiring proof for the #641 client migration: sibling-service clients (price, invoice) must be
 * built from the {@code loadBalancedRestClientBuilder} against Eureka service-id base URLs, while
 * the pos-tax client stays on the plain builder with its explicit Docker DNS address (ADR-0021
 * direct-call exception). Mirrors the pos-catalog {@code CatalogClientBuilderTest} pattern.
 */
class OrderClientBuilderTest {

    private static final String PRICE_BASE_URL = "http://price";
    private static final String INVOICE_BASE_URL = "http://invoice";
    private static final String TAX_BASE_URL = "http://pos-tax:8091";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    BuilderProbeConfiguration.class,
                    PriceClientConfig.class,
                    InvoiceClientConfig.class,
                    TaxClientConfig.class);

    @Test
    void priceAndInvoiceClientsUseLoadBalancedBuilderWithServiceIdBaseUrls() {
        contextRunner.run(context -> {
            RestClient.Builder plainBuilder = context.getBean("restClientBuilder", RestClient.Builder.class);
            RestClient.Builder loadBalancedBuilder =
                    context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

            context.getBean("priceServiceRestClient", RestClient.class);
            context.getBean("invoiceServiceRestClient", RestClient.class);

            verify(loadBalancedBuilder, times(1)).baseUrl(PRICE_BASE_URL);
            verify(loadBalancedBuilder, times(1)).baseUrl(INVOICE_BASE_URL);
            verify(plainBuilder, never()).baseUrl(PRICE_BASE_URL);
            verify(plainBuilder, never()).baseUrl(INVOICE_BASE_URL);
        });
    }

    @Test
    void taxClientUsesPlainBuilderWithExplicitDockerDnsBaseUrl() {
        contextRunner.run(context -> {
            RestClient.Builder plainBuilder = context.getBean("restClientBuilder", RestClient.Builder.class);
            RestClient.Builder loadBalancedBuilder =
                    context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

            context.getBean("taxServiceRestClient", RestClient.class);

            verify(plainBuilder, times(1)).baseUrl(TAX_BASE_URL);
            verify(loadBalancedBuilder, never()).baseUrl(TAX_BASE_URL);
        });
    }

    @Configuration
    static class BuilderProbeConfiguration {

        @Bean("restClientBuilder")
        @Primary
        RestClient.Builder restClientBuilder() {
            return mockBuilder();
        }

        @Bean("loadBalancedRestClientBuilder")
        RestClient.Builder loadBalancedRestClientBuilder() {
            return mockBuilder();
        }

        private RestClient.Builder mockBuilder() {
            RestClient.Builder builder = mock(RestClient.Builder.class);
            when(builder.baseUrl(PRICE_BASE_URL)).thenReturn(builder);
            when(builder.baseUrl(INVOICE_BASE_URL)).thenReturn(builder);
            when(builder.baseUrl(TAX_BASE_URL)).thenReturn(builder);
            when(builder.build()).thenReturn(mock(RestClient.class));
            return builder;
        }
    }
}
