package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantResolverTest {

    private static final UUID BOUND = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID DEFAULT = UUID.fromString("01900000-0000-7000-8000-000000000009");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void strictModeResolvesOnlyTheBoundTenant() {
        TenantResolver resolver = new TenantResolver(new TenancyProperties());
        assertThat(resolver.hasDefault()).isFalse();
        assertThat(resolver.resolve()).isEmpty();
        assertThatThrownBy(resolver::require).isInstanceOf(TenantContextMissingException.class);

        TenantContext.bind(BOUND);
        assertThat(resolver.require()).isEqualTo(BOUND);
    }

    @Test
    void transitionalDefaultAppliesOnlyWhenNothingIsBound() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(DEFAULT);
        TenantResolver resolver = new TenantResolver(properties);

        assertThat(resolver.hasDefault()).isTrue();
        assertThat(resolver.require()).isEqualTo(DEFAULT);
        TenantContext.bind(BOUND);
        assertThat(resolver.require())
                .as("an explicit binding wins over the default")
                .isEqualTo(BOUND);
    }
}
