package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.service.OutboxReplayService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /v1/outbox/replay} goes through the caller-scoped replay (ADR-0062 §3): the service
 * decides between the caller's own tenant and a platform operator's fan-out, so the controller must
 * never call the single-tenant {@code replaySince} that the Kafka command path uses.
 */
@DisplayName("OutboxAdminController — caller-scoped replay")
class OutboxAdminControllerTest {

    private final OutboxReplayService service = mock(OutboxReplayService.class);
    private final OutboxAdminController controller = new OutboxAdminController(service);

    @Test
    void replaysForTheCallerFromTheEpochWhenSinceIsOmitted() {
        when(service.replaySinceForCaller(Instant.EPOCH)).thenReturn(7);

        var response = controller.replay(null);

        verify(service).replaySinceForCaller(Instant.EPOCH);
        assertThat(response.getBody().since()).isEqualTo(Instant.EPOCH);
        assertThat(response.getBody().eventsQueued()).isEqualTo(7);
    }

    @Test
    void replaysForTheCallerFromTheGivenInstant() {
        Instant since = Instant.parse("2026-07-01T00:00:00Z");
        when(service.replaySinceForCaller(since)).thenReturn(2);

        var response = controller.replay(since);

        verify(service).replaySinceForCaller(since);
        assertThat(response.getBody().since()).isEqualTo(since);
        assertThat(response.getBody().eventsQueued()).isEqualTo(2);
    }
}
