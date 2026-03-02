package com.positivity.shopmanager.cap140;

import static org.mockito.Mockito.verify;

import com.positivity.shopmanager.internal.config.WorkorderStatusChangedEventListener;
import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.service.WorkorderStatusEventService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for WorkorderStatusChangedEventListener — CAP-140 Story #63.
 *
 * <p>
 * Verifies that the @EventListener method delegates to
 * {@link WorkorderStatusEventService#handleWorkorderStatusChanged(WorkorderStatusChangedEvent)}
 * exactly once with the unmodified event payload (AC8).
 *
 * Issue: #63
 */
@ExtendWith(MockitoExtension.class)
class WorkorderStatusChangedEventListenerTest {

    @Mock
    WorkorderStatusEventService service;

    @InjectMocks
    WorkorderStatusChangedEventListener listener;

    // -------------------------------------------------------------------------
    // AC8 — Listener delegates to WorkorderStatusEventService exactly once
    // -------------------------------------------------------------------------

    @Test
    void onWorkorderStatusChanged_delegatesToServiceWithSameEventExactlyOnce() {
        // Arrange
        WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "DRAFT",
                Instant.now(),
                UUID.randomUUID());

        // Act
        listener.onWorkorderStatusChanged(event);

        // Assert — listener must forward the exact event instance to the service
        verify(service).handleWorkorderStatusChanged(event);
    }
}
