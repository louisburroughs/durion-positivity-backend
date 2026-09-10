package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantIteratorTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID B = UUID.fromString("01900000-0000-7000-8000-000000000002");

    @Test
    void bindsEachTenantInTurnAndContinuesPastAFailure() {
        TenancyProperties properties = new TenancyProperties();
        properties.setTenants(List.of(A, B));
        TenantIterator iterator = new TenantIterator(new StaticTenantRegistry(properties));
        List<UUID> seen = new ArrayList<>();

        int completed = iterator.forEachActiveTenant(tenant -> {
            seen.add(TenantContext.require());
            if (tenant.equals(A)) {
                throw new IllegalStateException("tenant A is broken");
            }
        });

        assertThat(seen).containsExactly(A, B);
        assertThat(completed).isEqualTo(1);
        assertThat(TenantContext.current()).as("nothing leaks after the loop").isEmpty();
    }

    @Test
    void staticRegistryFallsBackToTheDefaultTenantAndThenToNothing() {
        TenancyProperties withDefault = new TenancyProperties();
        withDefault.setDefaultTenantId(A);
        assertThat(new StaticTenantRegistry(withDefault).activeTenantIds()).containsExactly(A);

        TenantIterator empty = new TenantIterator(new StaticTenantRegistry(new TenancyProperties()));
        assertThat(empty.forEachActiveTenant(tenant -> {
                    throw new AssertionError("must not run");
                }))
                .isZero();
    }
}
