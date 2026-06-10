package com.positivity.catalog.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CatalogClientBuilderTest {

    private static final String INVENTORY_BASE_URL = "http://inventory";
    private static final String PRICE_BASE_URL = "http://price";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestClientConfiguration.class, BuilderProbeConfiguration.class);

    @Test
    void inventoryAndPricingClientsUseLoadBalancedBuilderWithDirectServiceBaseUrls() {
        contextRunner.run(context -> {
            RestClient.Builder plainBuilder = context.getBean("restClientBuilder", RestClient.Builder.class);
            RestClient.Builder loadBalancedBuilder =
                    context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

            context.getBean(InventoryClientImpl.class);
            context.getBean(PricingClientImpl.class);

            verify(loadBalancedBuilder, times(1)).baseUrl(INVENTORY_BASE_URL);
            verify(loadBalancedBuilder, times(1)).baseUrl(PRICE_BASE_URL);
            verify(loadBalancedBuilder, times(2)).build();
            verify(plainBuilder, never()).baseUrl(INVENTORY_BASE_URL);
            verify(plainBuilder, never()).baseUrl(PRICE_BASE_URL);
            verify(plainBuilder, never()).build();
        });
    }

    @Test
    void inventoryClientUsesDirectServiceAvailabilityQueryPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        InventoryClientImpl client = new InventoryClientImpl(builder, "inventory", "/v1/inventory");
        UUID locationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        mockServer
                .expect(requestTo(INVENTORY_BASE_URL
                        + "/v1/inventory/availability/by-sku?productSku=SKU-123&locationId=" + locationId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{" + "\"onHandQuantity\":12,"
                                + "\"allocatedQuantity\":4,"
                                + "\"availableToPromiseQuantity\":8,"
                                + "\"unitOfMeasure\":\"EA\""
                                + "}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchAvailability("SKU-123", locationId)).isPresent();
        mockServer.verify();
    }

    @Test
    void inventoryClientUsesDirectServiceLeadTimePath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        InventoryClientImpl client = new InventoryClientImpl(builder, "inventory", "/v1/inventory");
        UUID productId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID locationId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        mockServer
                .expect(requestTo(INVENTORY_BASE_URL + "/v1/inventory/availability/lead-time?productId=" + productId
                        + "&locationId=" + locationId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{" + "\"minDays\":1,"
                                + "\"maxDays\":3,"
                                + "\"displayText\":\"1-3 days\","
                                + "\"source\":\"INVENTORY\","
                                + "\"confidence\":\"HIGH\","
                                + "\"asOf\":\"2026-05-05T12:00:00Z\""
                                + "}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchLeadTime(productId, locationId)).isPresent();
        mockServer.verify();
    }

    @Test
    void pricingClientUsesDirectServiceQuotesPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PricingClientImpl client = new PricingClientImpl(builder, "price", "/v1/price");
        UUID productId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID locationId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID customerTierId = UUID.fromString("66666666-6666-6666-6666-666666666666");

        mockServer
                .expect(requestTo(PRICE_BASE_URL + "/v1/price/quotes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-catalog-service"))
                .andExpect(header("X-Authorities", "pricing:price_book:view"))
                .andRespond(withSuccess(
                        "{" + "\"msrp\":{\"amount\":19.99,\"currency\":\"USD\"},"
                                + "\"unitPrice\":{\"amount\":17.49,\"currency\":\"USD\"},"
                                + "\"priceSource\":\"PRICE_BOOK\""
                                + "}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchPrice(productId, locationId, customerTierId)).isPresent();
        mockServer.verify();
    }

    @Configuration
    static class TestClientConfiguration {

        @Bean
        InventoryClientImpl inventoryClientImpl(
                @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder) {
            return new InventoryClientImpl(loadBalancedRestClientBuilder, "inventory", "/v1/inventory");
        }

        @Bean
        PricingClientImpl pricingClientImpl(
                @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder) {
            return new PricingClientImpl(loadBalancedRestClientBuilder, "price", "/v1/price");
        }
    }

    @Configuration
    static class BuilderProbeConfiguration {

        @Bean("restClientBuilder")
        @Primary
        RestClient.Builder restClientBuilder() {
            return mockBuilder(INVENTORY_BASE_URL, PRICE_BASE_URL);
        }

        @Bean("loadBalancedRestClientBuilder")
        RestClient.Builder loadBalancedRestClientBuilder() {
            return mockBuilder(INVENTORY_BASE_URL, PRICE_BASE_URL);
        }

        private RestClient.Builder mockBuilder(String... baseUrls) {
            RestClient.Builder builder = mock(RestClient.Builder.class);
            for (String baseUrl : baseUrls) {
                when(builder.baseUrl(baseUrl)).thenReturn(builder);
            }
            when(builder.build()).thenReturn(mock(RestClient.class));
            return builder;
        }
    }
}
