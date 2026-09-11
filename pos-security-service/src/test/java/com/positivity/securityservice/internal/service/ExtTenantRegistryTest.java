package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.PlatformTenant;
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

    /**
     * The replica seeds the platform tenant as {@code ACTIVE} (V3__ext_tenant.sql) so slug
     * resolution and the platform bootstrap can read that row, but {@link
     * com.positivity.tenancy.TenantRegistry#activeTenantIds()} forbids handing it to a {@code
     * TenantIterator} sweep: it is control-plane data owned by {@code pos-tenant} (ADR-0062 §7), and
     * {@code @PlatformScoped} exists precisely so platform work is swept separately. The registry
     * must filter it out itself, as StaticTenantRegistry and RemoteTenantRegistry do.
     */
    @Test
    void platformTenantIsExcludedEvenThoughTheReplicaSeedsItActive() {
        ExtTenantRepository repository = mock(ExtTenantRepository.class);
        UUID a = UUID.fromString("01990000-0000-7000-8000-000000000001");
        UUID b = UUID.fromString("01990000-0000-7000-8000-000000000002");
        when(repository.findByStatusOrderByTenantIdAsc("ACTIVE"))
                .thenReturn(List.of(
                        ExtTenant.builder().tenantId(PlatformTenant.ID).build(),
                        ExtTenant.builder().tenantId(a).build(),
                        ExtTenant.builder().tenantId(b).build()));

        assertThat(new ExtTenantRegistry(repository).activeTenantIds())
                .doesNotContain(PlatformTenant.ID)
                .containsExactly(a, b);
    }

    /** The platform tenant alone leaves nothing to sweep, rather than falling back to itself. */
    @Test
    void platformTenantOnlyReplicaYieldsNoActiveTenants() {
        ExtTenantRepository repository = mock(ExtTenantRepository.class);
        when(repository.findByStatusOrderByTenantIdAsc("ACTIVE"))
                .thenReturn(
                        List.of(ExtTenant.builder().tenantId(PlatformTenant.ID).build()));

        assertThat(new ExtTenantRegistry(repository).activeTenantIds()).isEmpty();
    }
}
