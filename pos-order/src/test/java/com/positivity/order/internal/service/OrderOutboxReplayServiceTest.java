package com.positivity.order.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.repository.OutboxEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2579 (ADR-0062 §3): a replay re-queues only the facts of the tenant the command was consumed under, so an
 * unbound call fails closed instead of reaching the global outbox.
 */
@DisplayName("OrderOutboxReplayService — tenant-bound replay of order.events.v1")
class OrderOutboxReplayServiceTest {

    private static final Instant SINCE = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-10-07T11:00:00Z");

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final OrderOutboxReplayService service = new OrderOutboxReplayService(outbox);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("with no tenant bound, neither replay touches the outbox")
    void unboundTenantFailsClosed() {
        TenantContext.clear();

        assertThatExceptionOfType(TenantContextMissingException.class).isThrownBy(() -> service.replaySince(SINCE));
        assertThatExceptionOfType(TenantContextMissingException.class)
                .isThrownBy(() -> service.replayBetween(SINCE, UNTIL));
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("a bound replay re-queues that tenant's order.events.v1 rows only")
    void boundTenantReplaysItsOwnFacts() {
        TenantContext.bind(TENANT_A);
        when(outbox.markForReplayBetween(TENANT_A, "order.events.v1", SINCE, UNTIL))
                .thenReturn(3);
        when(outbox.markForReplaySince(TENANT_A, "order.events.v1", SINCE)).thenReturn(5);

        assertThat(service.replayBetween(SINCE, UNTIL)).isEqualTo(3);
        assertThat(service.replaySince(SINCE)).isEqualTo(5);
        verify(outbox).markForReplayBetween(TENANT_A, "order.events.v1", SINCE, UNTIL);
        verify(outbox).markForReplaySince(TENANT_A, "order.events.v1", SINCE);
    }
}
