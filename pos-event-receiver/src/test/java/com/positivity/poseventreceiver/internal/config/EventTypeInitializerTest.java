package com.positivity.poseventreceiver.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.events.EventTypeRegistration;
import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.internal.entity.EventType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the pos-event-receiver {@link EventTypeInitializer}.
 *
 * <p>
 * This module is the one exception to the REST-based registration pattern: it
 * writes its own event types through {@link EventDao} directly, because calling
 * its own REST API at startup would be circular. That makes the upsert branch —
 * update an existing row versus create a new one — local logic worth pinning
 * down, along with the shared "a failure must not block startup" contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTypeInitializer — direct-DAO event-type registration")
class EventTypeInitializerTest {

    @Mock
    private EventDao eventDao;

    @InjectMocks
    private EventTypeInitializer sut;

    @Captor
    private ArgumentCaptor<EventType> savedEventType;

    @Test
    @DisplayName("run() saves one event type per declared registration")
    void run_savesEveryEventType() {
        when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());

        sut.run(null);

        List<EventTypeRegistration> declared = EventTypes.all();
        assertThat(declared).isNotEmpty();
        verify(eventDao, times(declared.size())).saveEventType(any(EventType.class));
    }

    @Test
    @DisplayName("run() creates a new event type when none exists for the type code")
    void run_whenTypeAbsent_createsNewEventType() {
        when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());

        sut.run(null);

        verify(eventDao, times(EventTypes.all().size())).saveEventType(savedEventType.capture());

        EventTypeRegistration first = EventTypes.all().getFirst();
        assertThat(savedEventType.getAllValues())
                .extracting(EventType::getTypeCode)
                .contains(first.getTypeCode());
    }

    @Test
    @DisplayName("run() updates the existing row in place when the type code is already registered")
    void run_whenTypeExists_updatesExistingEventType() {
        EventTypeRegistration first = EventTypes.all().getFirst();
        EventType existing = new EventType(first.getTypeCode(), "stale description", first.getApiVersion(), 1L, 2L, 3L);

        when(eventDao.getEventTypeByCode(anyString()))
                .thenAnswer(invocation -> first.getTypeCode().equals(invocation.getArgument(0))
                        ? Optional.of(existing)
                        : Optional.empty());

        sut.run(null);

        verify(eventDao, times(EventTypes.all().size())).saveEventType(savedEventType.capture());

        assertThat(savedEventType.getAllValues())
                .filteredOn(saved -> saved.getTypeCode().equals(first.getTypeCode()))
                .singleElement()
                .satisfies(saved -> {
                    // The same instance is mutated and re-saved, not replaced.
                    assertThat(saved).isSameAs(existing);
                    assertThat(saved.getDescription()).isEqualTo(first.getDescription());
                    assertThat(saved.getP50Micros()).isEqualTo(first.getP50Micros());
                    assertThat(saved.getP95Micros()).isEqualTo(first.getP95Micros());
                    assertThat(saved.getP99Micros()).isEqualTo(first.getP99Micros());
                });
    }

    @Test
    @DisplayName("run() does not propagate a DAO failure — startup must not block on registration")
    void run_whenSaveFails_doesNotPropagate() {
        when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());
        when(eventDao.saveEventType(any(EventType.class))).thenThrow(new RuntimeException("db unavailable"));

        assertThatNoException().isThrownBy(() -> sut.run(null));
    }

    @Test
    @DisplayName("run() keeps registering the remaining types after one failure")
    void run_whenOneRegistrationFails_continuesWithRemaining() {
        when(eventDao.getEventTypeByCode(anyString())).thenReturn(Optional.empty());
        when(eventDao.saveEventType(any(EventType.class)))
                .thenThrow(new RuntimeException("transient error"))
                .thenReturn(null);

        assertThatNoException().isThrownBy(() -> sut.run(null));

        verify(eventDao, times(EventTypes.all().size())).saveEventType(any(EventType.class));
    }
}
