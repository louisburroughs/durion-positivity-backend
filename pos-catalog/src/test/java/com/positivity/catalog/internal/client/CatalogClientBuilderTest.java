package com.positivity.catalog.internal.client;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

class CatalogClientBuilderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestClientConfiguration.class, BuilderProbeConfiguration.class);

    @Test
    void inventoryAndPricingClientsUseLoadBalancedBuilderWithDefaultGatewayBaseUrl() {
        contextRunner.run(context -> {
            RestClient.Builder plainBuilder = context.getBean("restClientBuilder", RestClient.Builder.class);
            RestClient.Builder loadBalancedBuilder =
                    context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

            context.getBean(InventoryClientImpl.class);
            context.getBean(PricingClientImpl.class);

            verify(loadBalancedBuilder, times(2)).baseUrl("http://api-gateway");
            verify(loadBalancedBuilder, times(2)).build();
            verify(plainBuilder, never()).baseUrl("http://api-gateway");
            verify(plainBuilder, never()).build();
        });
    }

    @Configuration
    static class TestClientConfiguration {

        @Bean
        InventoryClientImpl inventoryClientImpl(
                @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder) {
            return new InventoryClientImpl(loadBalancedRestClientBuilder, "http://api-gateway");
        }

        @Bean
        PricingClientImpl pricingClientImpl(
                @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder) {
            return new PricingClientImpl(loadBalancedRestClientBuilder, "http://api-gateway");
        }
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
            when(builder.baseUrl("http://api-gateway")).thenReturn(builder);
            when(builder.build()).thenReturn(mock(RestClient.class));
            return builder;
        }
    }
}