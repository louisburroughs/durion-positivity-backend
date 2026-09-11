package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import com.positivity.warranty.internal.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The replay marks only the bound tenant's rows, and refuses to run at all when nothing is bound:
 * an unbound replay would otherwise re-send every tenant's events (ADR-0062 §3).
 */
@ExtendWith(MockitoExtension.class)
class OutboxReplayServiceImplTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final Instant SINCE = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-09-02T00:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void clearBinding() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("replaySince marks only the bound tenant's rows")
    void replaySinceIsScopedToTheBoundTenant() {
        when(outboxEventRepository.markForReplaySince(TENANT, SINCE)).thenReturn(3);
        OutboxReplayServiceImpl service = new OutboxReplayServiceImpl(outboxEventRepository);

        int queued = TenantContext.callAs(TENANT, () -> service.replaySince(SINCE));

        assertThat(queued).isEqualTo(3);
        verify(outboxEventRepository).markForReplaySince(eq(TENANT), eq(SINCE));
    }

    @Test
    @DisplayName("replayBetween marks only the bound tenant's rows")
    void replayBetweenIsScopedToTheBoundTenant() {
        when(outboxEventRepository.markForReplayBetween(TENANT, SINCE, UNTIL)).thenReturn(2);
        OutboxReplayServiceImpl service = new OutboxReplayServiceImpl(outboxEventRepository);

        int queued = TenantContext.callAs(TENANT, () -> service.replayBetween(SINCE, UNTIL));

        assertThat(queued).isEqualTo(2);
        verify(outboxEventRepository).markForReplayBetween(eq(TENANT), eq(SINCE), eq(UNTIL));
    }

    @Test
    @DisplayName("an unbound replay fails closed and touches nothing")
    void unboundReplayFailsClosed() {
        OutboxReplayServiceImpl service = new OutboxReplayServiceImpl(outboxEventRepository);

        assertThatThrownBy(() -> service.replaySince(SINCE)).isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> service.replayBetween(SINCE, UNTIL)).isInstanceOf(TenantContextMissingException.class);

        verifyNoInteractions(outboxEventRepository);
    }
}
