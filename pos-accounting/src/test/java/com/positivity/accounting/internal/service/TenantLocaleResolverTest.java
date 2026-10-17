package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtTenant;
import com.positivity.accounting.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenantResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tenant locale read (#2618): the stored value, else en-US (ADR-0030 section 2). */
@DisplayName("TenantLocaleResolver")
class TenantLocaleResolverTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");

    private final ExtTenantRepository tenants = mock(ExtTenantRepository.class);
    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private final TenantLocaleResolver resolver = new TenantLocaleResolver(tenants, tenantResolver);

    @Test
    @DisplayName("returns the stored locale")
    void storedValue() {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(row("fr-CA")));

        assertThat(resolver.localeOf(TENANT)).isEqualTo("fr-CA");
    }

    @Test
    @DisplayName("a tenant with no row, or a row without a locale, is en-US")
    void defaultsToEnUs() {
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());
        assertThat(resolver.localeOf(TENANT)).isEqualTo("en-US");

        when(tenants.findById(TENANT)).thenReturn(Optional.of(row("  ")));
        assertThat(resolver.localeOf(TENANT)).isEqualTo("en-US");
    }

    @Test
    @DisplayName("localeOfCurrentTenant reads the bound tenant")
    void currentTenant() {
        when(tenantResolver.require()).thenReturn(TENANT);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(row("de-DE")));

        assertThat(resolver.localeOfCurrentTenant()).isEqualTo("de-DE");
    }

    private static ExtTenant row(String locale) {
        ExtTenant row = new ExtTenant();
        row.setExtTenantId(TENANT);
        row.setLocale(locale);
        return row;
    }
}
