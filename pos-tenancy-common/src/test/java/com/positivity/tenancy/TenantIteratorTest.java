package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    void aRegistryThatCannotVouchForItsListIsReportedIncomplete() {
        TenancyProperties properties = new TenancyProperties();
        properties.setTenants(List.of(A, B));

        assertThat(new TenantIterator(new StaticTenantRegistry(properties))
                        .sweep(tenant -> {})
                        .completeTenantList())
                .as("a registry that is authoritative by construction declares no freshness and counts as complete")
                .isTrue();
        assertThat(new TenantIterator(new FreshnessRegistry(List.of(A), false))
                        .sweep(tenant -> {})
                        .completeTenantList())
                .isFalse();
        assertThat(new TenantIterator(new FreshnessRegistry(List.of(A, B), true))
                        .sweep(tenant -> {})
                        .completeTenantList())
                .isTrue();
    }

    @Test
    void sweepCountsTenantsAlongsideTheCompletenessVerdict() {
        List<UUID> seen = new ArrayList<>();

        TenantIterator.Sweep sweep = new TenantIterator(new FreshnessRegistry(List.of(A, B), true)).sweep(seen::add);

        assertThat(sweep.completed()).isEqualTo(2);
        assertThat(sweep.completeTenantList()).isTrue();
        assertThat(seen).containsExactly(A, B);
    }

    @Test
    @DisplayName("the completeness verdict belongs to the list this sweep actually iterated, not to a later read of a"
            + " registry whose snapshot flips complete while the per-tenant work is still running")
    void completenessIsCapturedBeforeAnyTenantRunsNotAfterTheSweepFlipsIt() {
        // A registry that starts on an incomplete snapshot (the state this sweep must be graded
        // against) and flips to complete partway through the sweep, from inside the per-tenant work
        // itself — standing in for a RemoteTenantRegistry's background refresh landing, independently
        // of and concurrently with this sweep, exactly as any other caller sharing the registry could
        // trigger one, while a long per-tenant loop is still running under the snapshot it started
        // with.
        FlippingFreshnessRegistry registry = new FlippingFreshnessRegistry(List.of(A, B));

        TenantIterator.Sweep sweep = new TenantIterator(registry).sweep(tenant -> registry.markComplete());

        assertThat(registry.hasCompleteSnapshot())
                .as("by the time the sweep is over, the registry itself now reports complete")
                .isTrue();
        assertThat(sweep.completeTenantList())
                .as("but the verdict must belong to the list as it was when this sweep started, not to the"
                        + " registry's state now that the sweep is over")
                .isFalse();
    }

    private record FreshnessRegistry(List<UUID> tenants, boolean complete)
            implements TenantRegistry, TenantRegistryFreshness {

        @Override
        public List<UUID> activeTenantIds() {
            return tenants;
        }

        @Override
        public boolean hasCompleteSnapshot() {
            return complete;
        }
    }

    /** Reports its snapshot incomplete until {@link #markComplete()} is called, then complete forever after. */
    private static final class FlippingFreshnessRegistry implements TenantRegistry, TenantRegistryFreshness {
        private final List<UUID> tenants;
        private volatile boolean complete;

        FlippingFreshnessRegistry(List<UUID> tenants) {
            this.tenants = tenants;
        }

        @Override
        public List<UUID> activeTenantIds() {
            return tenants;
        }

        @Override
        public boolean hasCompleteSnapshot() {
            return complete;
        }

        void markComplete() {
            complete = true;
        }
    }
}
