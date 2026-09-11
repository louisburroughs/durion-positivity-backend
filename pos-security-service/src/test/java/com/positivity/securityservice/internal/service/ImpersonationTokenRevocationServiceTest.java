package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The tenant-independent half of impersonation-token revocation (ADR-0062 §7, WS2b-4): an operator
 * may hold a live support token in any tenant, so ending their access has to reach every one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImpersonationTokenRevocationService")
class ImpersonationTokenRevocationServiceTest {

    private static final UUID OPERATOR = UUID.fromString("01990000-0000-7000-8000-0000000000aa");
    private static final UUID TENANT_A = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("01990000-0000-7000-8000-0000000000a2");

    @Mock
    private ExtTenantRepository extTenantRepository;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private ImpersonationTokenRevocationService sut;

    @AfterEach
    void clearBinding() {
        TenantContext.clear();
    }

    private static ExtTenant tenant(UUID id, String slug, String status) {
        return ExtTenant.builder()
                .tenantId(id)
                .slug(slug)
                .displayName(slug)
                .status(status)
                .aggregateVersion(1)
                .updatedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("sweeps every replica tenant plus the platform tenant, each with that tenant bound")
    void revokeForOperator_sweepsEveryTenantWithItBound() {
        when(extTenantRepository.findAll())
                .thenReturn(List.of(tenant(TENANT_A, "acme", "ACTIVE"), tenant(TENANT_B, "dormant", "SUSPENDED")));
        List<UUID> boundDuringCall = new ArrayList<>();
        when(jwtService.revokeImpersonationTokensMintedBy(OPERATOR)).thenAnswer(invocation -> {
            boundDuringCall.add(TenantContext.current().orElse(null));
            return 1;
        });

        int revoked = sut.revokeForOperator(OPERATOR);

        assertThat(revoked).isEqualTo(3);
        // A suspended tenant is swept too: its rows are still readable with the token until it
        // expires, and a tenant's status is not a revocation.
        assertThat(boundDuringCall).containsExactly(PlatformTenant.ID, TENANT_A, TENANT_B);
        assertThat(TenantContext.current()).as("the binding is restored").isEmpty();
    }

    @Test
    @DisplayName("a tenant that fails does not stop the sweep")
    void revokeForOperator_oneTenantFails_continues() {
        when(extTenantRepository.findAll())
                .thenReturn(List.of(tenant(TENANT_A, "acme", "ACTIVE"), tenant(TENANT_B, "beta", "ACTIVE")));
        when(jwtService.revokeImpersonationTokensMintedBy(OPERATOR)).thenAnswer(invocation -> {
            UUID bound = TenantContext.current().orElseThrow();
            if (TENANT_A.equals(bound)) {
                throw new IllegalStateException("tenant A is unreachable");
            }
            return 2;
        });

        int revoked = sut.revokeForOperator(OPERATOR);

        // The platform tenant and tenant B still answered; only tenant A was lost.
        assertThat(revoked).isEqualTo(4);
    }

    @Test
    @DisplayName("an operator with no tokens anywhere revokes nothing and still visits every tenant")
    void revokeForOperator_noTokens_isANoOp() {
        when(extTenantRepository.findAll()).thenReturn(List.of(tenant(TENANT_A, "acme", "ACTIVE")));
        when(jwtService.revokeImpersonationTokensMintedBy(any())).thenReturn(0);

        assertThat(sut.revokeForOperator(OPERATOR)).isZero();

        verify(jwtService, org.mockito.Mockito.times(2)).revokeImpersonationTokensMintedBy(OPERATOR);
        verify(extTenantRepository).findAll();
        verifyNoMoreInteractions(extTenantRepository);
    }
}
