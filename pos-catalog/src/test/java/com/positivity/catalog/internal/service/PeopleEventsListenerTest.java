package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.ExtSkillReplica;
import com.positivity.catalog.internal.repository.ExtSkillReplicaRepository;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
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

/** CAP-329: {@code people.skill.updated} mirrors into {@code ext_skill}; other people facts are acknowledged. */
class PeopleEventsListenerTest {

    private static final String EVENT_ID = "01990000-0000-7000-8000-000000000201";
    private static final UUID SKILL_ID = UUID.fromString("01990000-0000-7000-8000-000000000041");

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtSkillReplicaRepository skillRepository = mock(ExtSkillReplicaRepository.class);

    private PeopleEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleEventsListener(
                clock,
                new ObjectMapper(),
                processedEventRepository,
                skillRepository,
                mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(skillRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String skillEvent(long aggregateVersion, boolean active) {
        return """
                {"eventId":"%s","eventType":"people.skill.updated","aggregateVersion":%d,
                 "payload":{"skillId":"%s","code":"BRAKES-MEDIUM_HEAVY","name":"Brakes (medium/heavy duty)",
                            "competenceCode":"BRAKES","minGvwrClass":4,"maxGvwrClass":8,"active":%s}}""".formatted(EVENT_ID, aggregateVersion, SKILL_ID, active);
    }

    @Test
    @DisplayName("a registry row is mirrored field for field and versioned by the envelope")
    void skillEvent_upsertsReplica() {
        listener.onPeopleEvent(skillEvent(1758000000000L, true));

        ArgumentCaptor<ExtSkillReplica> captor = ArgumentCaptor.forClass(ExtSkillReplica.class);
        verify(skillRepository).save(captor.capture());
        ExtSkillReplica row = captor.getValue();
        assertThat(row.getSkillId()).isEqualTo(SKILL_ID);
        assertThat(row.getCode()).isEqualTo("BRAKES-MEDIUM_HEAVY");
        assertThat(row.getName()).isEqualTo("Brakes (medium/heavy duty)");
        assertThat(row.getCompetenceCode()).isEqualTo("BRAKES");
        assertThat(row.getMinGvwrClass()).isEqualTo(4);
        assertThat(row.getMaxGvwrClass()).isEqualTo(8);
        assertThat(row.isActive()).isTrue();
        assertThat(row.getAggregateVersion()).isEqualTo(1758000000000L);
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.now(clock));
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("a retirement keeps the row and flips active, so a requirement naming it can be told")
    void retirement_isApplied() {
        when(skillRepository.findById(SKILL_ID))
                .thenReturn(Optional.of(ExtSkillReplica.builder()
                        .skillId(SKILL_ID)
                        .active(true)
                        .aggregateVersion(1L)
                        .build()));

        listener.onPeopleEvent(skillEvent(2L, false));

        ArgumentCaptor<ExtSkillReplica> captor = ArgumentCaptor.forClass(ExtSkillReplica.class);
        verify(skillRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("a stale snapshot never moves the replica backwards, but is marked processed")
    void staleEvent_isSkipped() {
        when(skillRepository.findById(SKILL_ID))
                .thenReturn(Optional.of(ExtSkillReplica.builder()
                        .skillId(SKILL_ID)
                        .active(true)
                        .aggregateVersion(9L)
                        .build()));

        listener.onPeopleEvent(skillEvent(8L, false));

        verify(skillRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("other people facts on the topic are acknowledged and ignored")
    void otherPeopleFacts_areIgnored() {
        listener.onPeopleEvent("""
                {"eventId":"%s","eventType":"people.employee.updated","aggregateVersion":1,
                 "payload":{"employeeId":"01990000-0000-7000-8000-00000000000b","status":"ACTIVE"}}""".formatted(EVENT_ID));

        verify(skillRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void alreadyProcessedEvent_isANoOp() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        listener.onPeopleEvent(skillEvent(1L, true));

        verify(skillRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }
}
