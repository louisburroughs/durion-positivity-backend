package com.positivity.workorder.contract;

import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
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
    public ExtCustomerPartyReplicaRepository contractTestExtCustomerPartyReplicaRepository() {
        ExtCustomerPartyReplicaRepository mock = Mockito.mock(ExtCustomerPartyReplicaRepository.class);
        Mockito.when(mock.findById(Mockito.any()))
                .thenReturn(java.util.Optional.of(
                        ExtCustomerPartyReplica.builder().requirementsMet(true).build()));
        return mock;
    }
}
