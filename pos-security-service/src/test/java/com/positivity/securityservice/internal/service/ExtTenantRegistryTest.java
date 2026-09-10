package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtTenantRegistryTest {

    @Test
    void activeTenantsComeFromTheReplica() {
        ExtTenantRepository repository = mock(ExtTenantRepository.class);
        UUID a = UUID.fromString("01990000-0000-7000-8000-000000000001");
        UUID b = UUID.fromString("01990000-0000-7000-8000-000000000002");
        when(repository.findByStatusOrderByTenantIdAsc("ACTIVE"))
                .thenReturn(List.of(
                        ExtTenant.builder().tenantId(a).build(),
                        ExtTenant.builder().tenantId(b).build()));

        assertThat(new ExtTenantRegistry(repository).activeTenantIds()).containsExactly(a, b);
    }
}
