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
    public RestClient contractTestRestClient() {
        RestClient mockRestClient = Mockito.mock(RestClient.class, Mockito.RETURNS_DEEP_STUBS);

        // Mock the RestClient chain for customer requirements check
        // Using RETURNS_DEEP_STUBS handles the chaining automatically
        Mockito.when(mockRestClient.get().uri(Mockito.anyString()).retrieve().body(Boolean.class))
                .thenReturn(Boolean.TRUE);

        return mockRestClient;
    }
}
