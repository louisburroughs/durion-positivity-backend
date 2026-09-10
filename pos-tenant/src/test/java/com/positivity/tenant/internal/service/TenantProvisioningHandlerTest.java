package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenant.internal.entity.ProcessedEvent;
import com.positivity.tenant.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenantProvisioningHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private final TenantService tenantService = mock(TenantService.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final TenantProvisioningHandler handler =
            new TenantProvisioningHandler(tenantService, processed, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void appliesOnceAndRecordsTheEvent() {
        UUID tenantId = UUID.randomUUID();
        when(processed.existsById("e1")).thenReturn(false);

        handler.apply("e1", tenantId);

        verify(tenantService).markProvisioned(tenantId);
        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo("e1");
        assertThat(saved.getValue().getOwner()).isEqualTo("security");
        assertThat(saved.getValue().getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void redeliveryIsANoOp() {
        when(processed.existsById("e1")).thenReturn(true);
        handler.apply("e1", UUID.randomUUID());
        verify(tenantService, never()).markProvisioned(any());
        verify(processed, never()).save(any());
    }
}
