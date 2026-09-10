package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.TenantMeResponse;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantQueryServiceTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private final ExtTenantRepository repository = mock(ExtTenantRepository.class);
    private final TenantQueryService service = new TenantQueryService(repository);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void answersTheBoundTenantFromTheReplica() {
        when(repository.findById(TENANT))
                .thenReturn(Optional.of(ExtTenant.builder()
                        .tenantId(TENANT)
                        .slug("acme")
                        .displayName("Acme")
                        .status("ACTIVE")
                        .build()));
        TenantContext.bind(TENANT);

        Optional<TenantMeResponse> body = service.currentTenant();

        assertThat(body).isPresent();
        assertThat(body.get().id()).isEqualTo(TENANT);
        assertThat(body.get().slug()).isEqualTo("acme");
        assertThat(body.get().status()).isEqualTo("ACTIVE");
    }

    @Test
    void unknownToTheReplicaIsEmptyAndUnboundIsRefused() {
        when(repository.findById(TENANT)).thenReturn(Optional.empty());
        TenantContext.bind(TENANT);
        assertThat(service.currentTenant()).isEmpty();

        TenantContext.clear();
        assertThatThrownBy(service::currentTenant).isInstanceOf(TenantContextMissingException.class);
    }
}
