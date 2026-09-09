package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantContext")
class TenantContextTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("01900000-0000-7000-8000-00000000000b");

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("starts unbound, and require() says which contract was broken")
    void unboundByDefault() {
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");
    }

    @Test
    @DisplayName("bind then clear")
    void bindAndClear() {
        TenantContext.bind(A);
        assertThat(TenantContext.require()).isEqualTo(A);
        TenantContext.clear();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("runAs restores the previous binding, including no binding at all")
    void runAsRestores() {
        assertThat(TenantContext.runAs(A, TenantContext::require)).isEqualTo(A);
        assertThat(TenantContext.current()).isEmpty();

        TenantContext.bind(A);
        TenantContext.runAs(B, () -> assertThat(TenantContext.require()).isEqualTo(B));
        assertThat(TenantContext.require()).isEqualTo(A);
    }

    @Test
    @DisplayName("runAs restores even when the work throws")
    void runAsRestoresOnFailure() {
        TenantContext.bind(A);
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> {
                    throw new IllegalArgumentException("boom");
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.require()).isEqualTo(A);
    }

    @Test
    @DisplayName("a binding does not leak to another thread")
    void isThreadBound() throws InterruptedException {
        TenantContext.bind(A);
        UUID[] seen = new UUID[1];
        Thread other = new Thread(() -> seen[0] = TenantContext.current().orElse(null));
        other.start();
        other.join();
        assertThat(seen[0]).isNull();
    }
}
