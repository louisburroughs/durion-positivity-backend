package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.location.LocationUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The location replica keeps the owner's time zone (#2508): where a walk-in sale's business day ends.
 * Existing rows fill on the owner's replay, because an equal version applies.
 */
@DisplayName("LocationEventsListener — time zone (#2508)")
class LocationEventsListenerTimezoneTest {

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-7000-8000-000000002508");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtLocationReplicaRepository replica = mock(ExtLocationReplicaRepository.class);
    private final ExtLocationParentReplicaRepository parents = mock(ExtLocationParentReplicaRepository.class);
    private final LocationHierarchyService hierarchy = mock(LocationHierarchyService.class);
    private LocationEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        listener = new LocationEventsListener(
                Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper(),
                processedEvents,
                replica,
                parents,
                hierarchy,
                mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
        when(processedEvents.existsById(any())).thenReturn(false);
    }

    @Test
    @DisplayName("stores LocationUpdatedV1.timezone on the replica")
    void storesTimezone() {
        when(replica.findById(LOCATION_ID)).thenReturn(Optional.empty());

        listener.onLocationEvent(updated("e-tz-1", 1, "\"America/Chicago\""));

        assertThat(saved().getTimezone()).isEqualTo("America/Chicago");
    }

    @Test
    @DisplayName("a replay at the held version fills the time zone on an existing row; an absent one stays null")
    void replayFillsTimezone() {
        when(replica.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .code("LOC-107")
                        .active(true)
                        .aggregateVersion(3)
                        .build()));

        listener.onLocationEvent(updated("e-tz-2", 3, "\"America/Chicago\""));
        assertThat(saved().getTimezone()).isEqualTo("America/Chicago");
    }

    @Test
    @DisplayName("a fact without a time zone stores null")
    void absentTimezoneIsNull() {
        when(replica.findById(LOCATION_ID)).thenReturn(Optional.empty());

        listener.onLocationEvent(updated("e-tz-3", 1, "null"));

        assertThat(saved().getTimezone()).isNull();
    }

    private ExtLocationReplica saved() {
        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(replica).save(captor.capture());
        return captor.getValue();
    }

    private static String updated(String eventId, long version, String timezoneJson) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"locationId":"%s","name":"Main","code":"LOC-107","active":true,"timezone":%s}}
                """.formatted(eventId, LocationUpdatedV1.EVENT_TYPE, version, LOCATION_ID, timezoneJson);
    }
}
