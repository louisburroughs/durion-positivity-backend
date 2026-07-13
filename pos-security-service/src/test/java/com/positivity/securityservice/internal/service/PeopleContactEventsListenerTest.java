package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.ExtPersonReplicaRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the {@code users.person_id} projection consumer (amended ADR-0043 §2, #876).
 */
class PeopleContactEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtPersonReplicaRepository extPersonReplicaRepository = mock(ExtPersonReplicaRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private PeopleContactEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleContactEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEventRepository, extPersonReplicaRepository, userRepository);
        when(processedEventRepository.existsById(any())).thenReturn(false);
    }

    private String linkUpdated(String eventId, UUID personId, String status) {
        return """
                {"eventId":"%s","eventType":"people-contact.user-person-link.updated",
                 "aggregateVersion":10,
                 "payload":{"linkId":"%s","personId":"%s","username":"jane","status":"%s","linkType":"PRIMARY"}}
                """.formatted(eventId, LINK_ID, personId, status);
    }

    private String linkRemoved(String eventId, UUID personId) {
        return """
                {"eventId":"%s","eventType":"people-contact.user-person-link.removed",
                 "payload":{"linkId":"%s","personId":"%s","username":"jane"}}
                """.formatted(eventId, LINK_ID, personId);
    }

    @Test
    @DisplayName("Active link fact sets users.person_id on the matching username")
    void activeLinkSetsProjection() {
        User user = new User();
        user.setUsername("jane");
        when(userRepository.findByUsername("jane")).thenReturn(Optional.of(user));

        listener.onPeopleContactEvent(linkUpdated("00000000-0000-7000-8000-000000000e01", PERSON_ID, "ACTIVE"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPersonId()).isEqualTo(PERSON_ID);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("Removed link fact clears the projection only when it names the projected person")
    void removalGuardsOnPersonId() {
        User user = new User();
        user.setUsername("jane");
        user.setPersonId(PERSON_ID);
        when(userRepository.findByUsername("jane")).thenReturn(Optional.of(user));

        // A stale removal for a different (older) link must not clear the newer projection.
        listener.onPeopleContactEvent(linkRemoved("00000000-0000-7000-8000-000000000e02", OTHER_PERSON_ID));
        verify(userRepository, never()).save(any());

        listener.onPeopleContactEvent(linkRemoved("00000000-0000-7000-8000-000000000e03", PERSON_ID));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPersonId()).isNull();
    }

    @Test
    @DisplayName("Duplicate events are skipped via processed_events")
    void duplicateEventsSkipped() {
        when(processedEventRepository.existsById("00000000-0000-7000-8000-000000000e04"))
                .thenReturn(true);

        listener.onPeopleContactEvent(linkUpdated("00000000-0000-7000-8000-000000000e04", PERSON_ID, "ACTIVE"));

        verify(userRepository, never()).findByUsername(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Link facts for usernames without a security account are marked processed and skipped")
    void unknownUsernameIsNoOp() {
        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());

        listener.onPeopleContactEvent(linkUpdated("00000000-0000-7000-8000-000000000e05", PERSON_ID, "ACTIVE"));

        verify(userRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }
}
