package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplicaVersionGuardTest {

    @Test
    void heldOlderThanIncomingIsNotStale() {
        assertThat(ReplicaVersionGuard.isStale(1L, 2L)).isFalse();
    }

    @Test
    void heldEqualToIncomingIsNotStale() {
        // Load-bearing (#1486): the publisher contract strictly advances aggregateVersion, so an
        // equal version means identical content, and POST .../facts/replay deliberately resends a
        // fact at the version already held to repair a replica row that is wrong or missing.
        // Treating equal as stale (the old `>=` skip) silently turns replay into a no-op — that was
        // #1486's operational trap. Equal MUST apply, not skip.
        assertThat(ReplicaVersionGuard.isStale(5L, 5L)).isFalse();
    }

    @Test
    void heldNewerThanIncomingIsStale() {
        assertThat(ReplicaVersionGuard.isStale(3L, 2L)).isTrue();
    }

    @Test
    void boundaryValuesAtZeroAreNotStale() {
        assertThat(ReplicaVersionGuard.isStale(0L, 0L)).isFalse();
    }

    @Test
    void boundaryValuesAtLongMaxAreNotStale() {
        assertThat(ReplicaVersionGuard.isStale(Long.MAX_VALUE, Long.MAX_VALUE)).isFalse();
    }

    @Test
    void heldOneBelowLongMaxIsNotStaleAgainstLongMax() {
        assertThat(ReplicaVersionGuard.isStale(Long.MAX_VALUE - 1, Long.MAX_VALUE))
                .isFalse();
    }

    @Test
    void heldAtLongMaxIsStaleAgainstAnyLowerIncoming() {
        assertThat(ReplicaVersionGuard.isStale(Long.MAX_VALUE, 0L)).isTrue();
    }
}
