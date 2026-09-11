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

    @Test
    @DisplayName("sweep reads the tenant list and its completeness from one atomic snapshot() call, never by"
            + " calling activeTenantIds() and separately consulting hasCompleteSnapshot() — a concurrent refresh"
            + " landing in between those two calls must not pair the pre-refresh list with a post-refresh"
            + " completeness verdict")
    void sweepNeverTearsTheListApartFromItsCompletenessVerdict() {
        // Stands in for a RemoteTenantRegistry: activeTenantIds() returns the list as it was before a
        // refresh, and — deterministically, as a side effect of that very call rather than on a timer or a
        // second thread — a refresh lands and flips completeness. A caller that reads the list via
        // activeTenantIds() and only afterward, separately, reads hasCompleteSnapshot() observes the
        // pre-refresh list paired with the post-refresh "complete" verdict: exactly the torn read the fix
        // closes. The registry's own snapshot() override is what a correctly fixed RemoteTenantRegistry
        // publishes atomically instead.
        TornOnActiveTenantIdsRegistry registry = new TornOnActiveTenantIdsRegistry(List.of(A, B));

        TenantIterator.Sweep sweep = new TenantIterator(registry).sweep(tenant -> {});

        assertThat(registry.activeTenantIdsCalls())
                .as("sweep must go through the atomic snapshot() accessor, not call activeTenantIds() itself")
                .isZero();
        assertThat(registry.hasCompleteSnapshotCalls())
                .as("sweep must go through the atomic snapshot() accessor, not call hasCompleteSnapshot() itself")
                .isZero();
        assertThat(sweep.completeTenantList())
                .as("the list this sweep iterated ([A, B]) was captured before the refresh landed, so it must"
                        + " not be marked complete just because a concurrent refresh (which the sweep never"
                        + " saw the result of) happened to land while the registry was being read")
                .isFalse();
    }

    /**
     * A {@link TenantRegistryFreshness} registry whose {@link #activeTenantIds()} triggers, as a side effect,
     * the same state change a background refresh would — deterministically reproducing the torn-read window
     * a two-call {@code activeTenantIds()} + {@code hasCompleteSnapshot()} read is exposed to, without relying
     * on real thread timing. {@link #snapshot()} is overridden to return the correct atomic pairing, standing
     * in for a registry (like the fixed {@code RemoteTenantRegistry}) that publishes list and completeness
     * together; the test above asserts {@link TenantIterator#sweep} reaches only that method.
     */
    private static final class TornOnActiveTenantIdsRegistry implements TenantRegistry, TenantRegistryFreshness {
        private final List<UUID> tenants;
        private boolean refreshLandedDuringActiveTenantIds;
        private int activeTenantIdsCalls;
        private int hasCompleteSnapshotCalls;

        TornOnActiveTenantIdsRegistry(List<UUID> tenants) {
            this.tenants = tenants;
        }

        @Override
        public List<UUID> activeTenantIds() {
            activeTenantIdsCalls++;
            // A concurrent refresh landing between this call and a later, separately timed
            // hasCompleteSnapshot() call — reproduced deterministically as this call's own side effect.
            refreshLandedDuringActiveTenantIds = true;
            return tenants;
        }

        @Override
        public boolean hasCompleteSnapshot() {
            hasCompleteSnapshotCalls++;
            return refreshLandedDuringActiveTenantIds;
        }

        @Override
        public TenantRegistry.Snapshot snapshot() {
            // The atomic pairing a fixed registry publishes: the list as it stood before the refresh,
            // correctly marked incomplete, with neither activeTenantIds() nor hasCompleteSnapshot() called.
            return new TenantRegistry.Snapshot(tenants, false);
        }

        int activeTenantIdsCalls() {
            return activeTenantIdsCalls;
        }

        int hasCompleteSnapshotCalls() {
            return hasCompleteSnapshotCalls;
        }
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
