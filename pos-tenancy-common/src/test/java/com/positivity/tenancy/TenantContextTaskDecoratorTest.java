package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTaskDecoratorTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID STALE = UUID.fromString("01900000-0000-7000-8000-00000000dead");

    private final TenantContextTaskDecorator decorator = new TenantContextTaskDecorator();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void propagatesTheSubmittingThreadsTenant() throws Exception {
        AtomicReference<UUID> seen = new AtomicReference<>();
        Runnable decorated = TenantContext.callAs(A, () -> decorator.decorate(() -> seen.set(TenantContext.require())));

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(seen.get()).isEqualTo(A);
    }

    @Test
    void thePooledThreadIsLeftUnboundAfterTheTask() {
        Runnable decorated = TenantContext.callAs(A, () -> decorator.decorate(() -> {}));
        TenantContext.bind(STALE);

        decorated.run();

        assertThat(TenantContext.current())
                .as("no restore of the worker's stale binding")
                .isEmpty();
    }

    @Test
    void anUnboundSubmitterClearsWhateverThePooledThreadCarried() {
        Runnable decorated =
                decorator.decorate(() -> assertThat(TenantContext.current()).isEmpty());
        TenantContext.bind(STALE);
        decorated.run();
    }
}
