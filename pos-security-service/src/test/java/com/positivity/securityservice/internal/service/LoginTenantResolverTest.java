package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class LoginTenantResolverTest {

    private static final UUID ACME = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final UUID DEFAULT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final ExtTenantRepository extTenants = mock(ExtTenantRepository.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private LoginTenantResolver resolver(UUID defaultTenant) {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(defaultTenant);
        return new LoginTenantResolver(extTenants, new TenantResolver(properties));
    }

    private void replica(String slug, String status) {
        when(extTenants.findBySlug(slug))
                .thenReturn(Optional.of(ExtTenant.builder()
                        .tenantId(ACME)
                        .slug(slug)
                        .status(status)
                        .build()));
    }

    @Test
    @DisplayName("the gateway's host-derived slug wins over the body slug")
    void headerSlugWins() {
        replica("acme", "ACTIVE");
        assertThat(resolver(DEFAULT).resolve("acme", "other")).isEqualTo(ACME);
    }

    @Test
    void bodySlugIsUsedWhenNoHeader() {
        replica("acme", "ACTIVE");
        assertThat(resolver(DEFAULT).resolve(" ", "acme")).isEqualTo(ACME);
    }

    @Test
    @DisplayName("an unknown or inactive slug is a bad-credentials 401, never a hint")
    void unknownOrInactiveSlugIsBadCredentials() {
        when(extTenants.findBySlug("ghost")).thenReturn(Optional.empty());
        replica("frozen", "SUSPENDED");
        LoginTenantResolver resolver = resolver(DEFAULT);

        assertThatThrownBy(() -> resolver.resolve(null, "ghost"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(LoginTenantResolver.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> resolver.resolve("frozen", null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(LoginTenantResolver.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("without a slug: the bound tenant, else the transitional default, else 401")
    void noSlugFallsBackToBoundOrDefault() {
        assertThat(resolver(DEFAULT).resolve(null, null)).isEqualTo(DEFAULT);
        TenantContext.bind(ACME);
        assertThat(resolver(DEFAULT).resolve(null, null)).isEqualTo(ACME);
        TenantContext.clear();
        assertThatThrownBy(() -> resolver(null).resolve(null, "")).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(extTenants);
    }
}
