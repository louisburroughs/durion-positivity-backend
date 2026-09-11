package com.positivity.mcp.internal.config;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.TenantContext;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantContextPropagation (ADR-0062 WS6)")
class TenantContextPropagationTest {

    private final TenantContextPropagation propagation = new TenantContextPropagation();

    @AfterEach
    void tearDown() {
        propagation.unregister();
        TenantContext.clear();
    }

    @Test
    @DisplayName("a captured context restores the bound tenant on another thread and clears it afterwards")
    void snapshotCarriesTheTenantAcrossThreads() throws Exception {
        propagation.register();
        TenantContext.bind(TENANT_A);
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
        TenantContext.clear();

        AtomicReference<Optional<UUID>> inside = new AtomicReference<>();
        AtomicReference<Optional<UUID>> after = new AtomicReference<>();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(snapshot.wrap(() -> inside.set(TenantContext.current())))
                    .get();
            other.submit(() -> after.set(TenantContext.current())).get();
        } finally {
            other.shutdownNow();
        }

        assertThat(inside.get()).contains(TENANT_A);
        assertThat(after.get())
                .as("the accessor's reset cleared the worker thread")
                .isEmpty();
    }
}
