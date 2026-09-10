package com.positivity.tenant.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The status machine of ADR-0062 §7, pinned transition by transition. */
class TenantStatusTest {

    @Test
    void pendingActivatesOrDecommissions() {
        assertThat(TenantStatus.PENDING.transitions())
                .containsExactlyInAnyOrder(TenantStatus.ACTIVE, TenantStatus.DECOMMISSIONED);
        assertThat(TenantStatus.PENDING.canTransitionTo(TenantStatus.SUSPENDED)).isFalse();
    }

    @Test
    void activeSuspendsOrDecommissions() {
        assertThat(TenantStatus.ACTIVE.transitions())
                .containsExactlyInAnyOrder(TenantStatus.SUSPENDED, TenantStatus.DECOMMISSIONED);
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.PENDING)).isFalse();
    }

    @Test
    void suspendedReactivatesOrDecommissions() {
        assertThat(TenantStatus.SUSPENDED.transitions())
                .containsExactlyInAnyOrder(TenantStatus.ACTIVE, TenantStatus.DECOMMISSIONED);
    }

    @Test
    void decommissionedIsTerminal() {
        assertThat(TenantStatus.DECOMMISSIONED.transitions()).isEmpty();
        assertThat(TenantStatus.DECOMMISSIONED.isTerminal()).isTrue();
        assertThat(TenantStatus.ACTIVE.isTerminal()).isFalse();
    }
}
