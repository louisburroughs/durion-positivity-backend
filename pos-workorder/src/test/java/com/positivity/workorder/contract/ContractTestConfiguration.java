package com.positivity.workorder.contract;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Test configuration for contract tests.
 * Provides a mock RestClient to avoid external service dependencies.
 */
@TestConfiguration
public class ContractTestConfiguration {

    @Bean
    @Primary
    public RestClient restClient() {
        RestClient mockRestClient = Mockito.mock(RestClient.class);
        RestClient.RequestHeadersUriSpec requestHeadersUriSpec = Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = Mockito.mock(RestClient.ResponseSpec.class);

        // Mock the RestClient chain for customer requirements check
        Mockito.when(mockRestClient.get()).thenReturn(requestHeadersUriSpec);
        Mockito.when(requestHeadersUriSpec.uri(Mockito.anyString())).thenReturn(requestHeadersUriSpec);
        Mockito.when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        Mockito.when(responseSpec.body(Boolean.class)).thenReturn(Boolean.TRUE);

        return mockRestClient;
    }
}
