package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TenantContextTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID B = UUID.fromString("01900000-0000-7000-8000-000000000002");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void unboundByDefault() {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TenantContext.isBound()).isFalse();
        assertThatThrownBy(TenantContext::require).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void bindMirrorsIntoMdcAndClearRemovesIt() {
        TenantContext.bind(A);
        assertThat(TenantContext.require()).isEqualTo(A);
        assertThat(MDC.get(TenantContext.MDC_KEY)).isEqualTo(A.toString());

        TenantContext.clear();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(MDC.get(TenantContext.MDC_KEY)).isNull();
    }

    @Test
    void runAsRestoresThePreviousBinding() {
        TenantContext.bind(A);
        TenantContext.runAs(B, () -> assertThat(TenantContext.require()).isEqualTo(B));
        assertThat(TenantContext.require()).isEqualTo(A);
    }

    @Test
    void runAsClearsWhenNothingWasBoundBefore() {
        TenantContext.runAs(B, () -> assertThat(TenantContext.require()).isEqualTo(B));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAsReturnsTheValueAndRethrowsRuntimeExceptionsUnchanged() {
        assertThat(TenantContext.callAs(A, () -> TenantContext.require().toString()))
                .isEqualTo(A.toString());
        assertThatThrownBy(() -> TenantContext.callAs(A, () -> {
                    throw new IllegalArgumentException("boom");
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("boom");
        assertThat(TenantContext.current()).as("cleared even after a failure").isEmpty();
    }

    @Test
    void callAsWrapsCheckedExceptions() {
        assertThatThrownBy(() -> TenantContext.callAs(A, () -> {
                    throw new java.io.IOException("io");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
    }
}
