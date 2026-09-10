package com.positivity.tenancy.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextIdentifierResolverTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final TenantContextIdentifierResolver resolver =
            new TenantContextIdentifierResolver(new TenantResolver(new TenancyProperties()));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void resolvesTheBoundTenantOrNull() {
        assertThat(resolver.resolveCurrentTenantIdentifier()).isNull();
        TenantContext.bind(A);
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(A);
    }

    @Test
    void neverGrantsAHibernateLevelBypass() {
        assertThat(resolver.isRoot(A)).isFalse();
        assertThat(resolver.validateExistingCurrentSessions()).isTrue();
    }

    @Test
    void registersItselfWithHibernate() {
        Map<String, Object> properties = new HashMap<>();
        resolver.customize(properties);
        assertThat(properties).containsEntry(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
