package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.security.RoleAssignmentChangedV1;
import com.positivity.people.internal.entity.ExtRoleAssignmentReplica;
import com.positivity.people.internal.entity.ProcessedEvent;
import com.positivity.people.internal.repository.ExtRoleAssignmentReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SecurityEventsListener} (ADR-0044 §6, durion#2155/#2160).
 *
 * <p>Modelled directly on {@code PeopleContactEventsListenerTest}, this module's exemplar replica
 * listener test — same dedup contract (an event type this listener does not consume still records
 * a {@code processed_events} row, the opposite of pos-order's replica listeners, because the
 * owner's manifest counts every fact in the window), same stale-version semantics (strictly-lower
 * only, since {@code aggregateVersion} is an LWW hint, not a strict JPA version), same
 * transient-error-rethrown / malformed-payload-swallowed split.
 *
 * <p>One deliberate difference from the exemplar: there is no delete/removal test here.
 * {@code RoleAssignmentChangedV1} has no "removed" counterpart — a revoke arrives as the same
 * event type with {@code revokedAt} populated, so "revoke updates the row" is covered as an
 * ordinary upsert, not a deletion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecurityEventsListener — role assignment replica")
class SecurityEventsListenerTest {

    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtRoleAssignmentReplicaRepository extRoleAssignmentReplicaRepository;

    private SecurityEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new SecurityEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                extRoleAssignmentReplicaRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extRoleAssignmentReplicaRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String roleAssignmentChanged(String eventId, long version, String revokedAtJson) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"assignmentId":"%s","userId":"%s","username":"ada",
                   "roleId":"%s","roleName":"SHOP_MANAGER","roleLocationScope":"ALL",
                   "effectiveStartDate":"2026-01-01T00:00:00","effectiveEndDate":null,
                   "revokedAt":%s,"tenantId":null}}
                """.formatted(
                eventId, RoleAssignmentChangedV1.EVENT_TYPE, version, ASSIGNMENT_ID, USER_ID, ROLE_ID, revokedAtJson);
    }

    private ExtRoleAssignmentReplica captureSaved() {
        ArgumentCaptor<ExtRoleAssignmentReplica> captor = ArgumentCaptor.forClass(ExtRoleAssignmentReplica.class);
        verify(extRoleAssignmentReplicaRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("delivery contract")
    class DeliveryContract {

        @Test
        @DisplayName("records a dedup row even for an event type this module does not consume")
        void ignoredTypeStillRecordsProcessedEvent() {
            listener.onSecurityEvent("""
                    {"eventId":"evt-1","eventType":"security.something.else","payload":{}}""");

            // Opposite of the pos-order replica listeners on purpose: the owner's manifest counts
            // every fact in the window, so omitting this row would look like drift and force a
            // replay that has nothing to repair.
            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo("evt-1");
            assertThat(captor.getValue().getOwner()).isEqualTo("security");
            assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("skips a message that is not JSON without recording it")
        void unparsableMessageIsSkipped() {
            listener.onSecurityEvent("{not json");

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips an envelope with no eventId, since nothing could de-duplicate it")
        void missingEventIdIsSkipped() {
            listener.onSecurityEvent(roleAssignmentChanged("", 1, "null"));

            verify(processedEventRepository, never()).save(any());
            verify(extRoleAssignmentReplicaRepository, never()).save(any());
        }

        @Test
        @DisplayName("no-ops on a replayed eventId")
        void replayIsIgnored() {
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);

            listener.onSecurityEvent(roleAssignmentChanged("evt-1", 1, "null"));

            verify(extRoleAssignmentReplicaRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error so the container retries instead of losing the fact")
        void transientErrorIsRethrown() {
            when(extRoleAssignmentReplicaRepository.findById(ASSIGNMENT_ID))
                    .thenThrow(new QueryTimeoutException("lock wait"));

            assertThatThrownBy(() -> listener.onSecurityEvent(roleAssignmentChanged("evt-1", 1, "null")))
                    .isInstanceOf(QueryTimeoutException.class);

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("swallows a malformed payload but still records the event as seen")
        void malformedPayloadIsSwallowed() {
            assertThatCode(() -> listener.onSecurityEvent("""
                            {"eventId":"evt-1","eventType":"%s","aggregateVersion":1,
                             "payload":{"assignmentId":"not-a-uuid"}}""".formatted(RoleAssignmentChangedV1.EVENT_TYPE)))
                    .doesNotThrowAnyException();

            verify(extRoleAssignmentReplicaRepository, never()).save(any());

            // NOT recorded in processed_events, which this test previously required. Returning
            // normally is what keeps a poison message from wedging the partition -- the offset
            // still commits -- so that property does not depend on the bookkeeping below.
            //
            // Recording a REJECTED payload is different from recording an ignored event type, even
            // though both end in "we wrote no replica row". An ignored type is not a missing fact;
            // a rejected one is, so the drift it causes is genuine and the replay it provokes is
            // the repair. Recording it makes existsById skip the event on every later delivery,
            // putting it beyond the reach of any replay or backfill -- turning a deferred write
            // into permanent loss. durion#2163 review: a role-assignment fact published before
            // roleLocationScope was added to the contract deserializes as malformed, so under the
            // old behaviour every such grant was lost with nothing failing to say so.
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("role assignment replica")
    class RoleAssignmentReplica {

        @Test
        @DisplayName("a grant fact creates a replica row")
        void grantCreatesReplicaRow() {
            listener.onSecurityEvent(roleAssignmentChanged("evt-1", 3, "null"));

            ExtRoleAssignmentReplica saved = captureSaved();
            assertThat(saved.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getUsername()).isEqualTo("ada");
            assertThat(saved.getRoleId()).isEqualTo(ROLE_ID);
            assertThat(saved.getRoleName()).isEqualTo("SHOP_MANAGER");
            assertThat(saved.getRoleLocationScope()).isEqualTo("ALL");
            assertThat(saved.getRevokedAt()).isNull();
            assertThat(saved.getAggregateVersion()).isEqualTo(3);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a revoke fact updates the replica row rather than deleting it")
        void revokeUpdatesReplicaRow() {
            ExtRoleAssignmentReplica existing = ExtRoleAssignmentReplica.builder()
                    .assignmentId(ASSIGNMENT_ID)
                    .userId(USER_ID)
                    .username("ada")
                    .roleId(ROLE_ID)
                    .roleName("SHOP_MANAGER")
                    .aggregateVersion(3)
                    .build();
            when(extRoleAssignmentReplicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existing));

            listener.onSecurityEvent(roleAssignmentChanged("evt-2", 4, "\"2026-08-11T09:00:00Z\""));

            ExtRoleAssignmentReplica saved = captureSaved();
            assertThat(saved.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(saved.getRevokedAt()).isEqualTo(NOW);
            assertThat(saved.getAggregateVersion()).isEqualTo(4);
            verify(extRoleAssignmentReplicaRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("ignores a snapshot strictly older than the replica")
        void olderSnapshotIsIgnored() {
            ExtRoleAssignmentReplica existing = ExtRoleAssignmentReplica.builder()
                    .assignmentId(ASSIGNMENT_ID)
                    .aggregateVersion(5)
                    .build();
            when(extRoleAssignmentReplicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existing));

            listener.onSecurityEvent(roleAssignmentChanged("evt-1", 4, "null"));

            verify(extRoleAssignmentReplicaRepository, never()).save(any());
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("re-applies a snapshot at the same version, since the version is only an LWW hint")
        void equalVersionReapplies() {
            ExtRoleAssignmentReplica existing = ExtRoleAssignmentReplica.builder()
                    .assignmentId(ASSIGNMENT_ID)
                    .aggregateVersion(5)
                    .build();
            when(extRoleAssignmentReplicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existing));

            listener.onSecurityEvent(roleAssignmentChanged("evt-1", 5, "null"));

            // The producer's version is an emission timestamp, not a strict JPA version, so
            // dropping an equal-version redelivery would risk discarding a legitimate update.
            // Re-applying is harmless because the payload is a full snapshot.
            assertThat(captureSaved().getAggregateVersion()).isEqualTo(5);
        }
    }
}
