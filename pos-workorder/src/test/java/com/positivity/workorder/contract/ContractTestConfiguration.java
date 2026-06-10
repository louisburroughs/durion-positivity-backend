package com.positivity.workorder.contract;

import com.positivity.workorder.internal.client.CustomerValidationClient;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration for contract tests.
 * Provides mock beans to avoid external service dependencies.
 */
@TestConfiguration
public class ContractTestConfiguration {

    @Bean
    @Primary
    public CustomerValidationClient contractTestCustomerValidationClient() {
        CustomerValidationClient mock = Mockito.mock(CustomerValidationClient.class);
        Mockito.when(mock.checkRequirementsMet(Mockito.any())).thenReturn(Boolean.TRUE);
        return mock;
    }
}
