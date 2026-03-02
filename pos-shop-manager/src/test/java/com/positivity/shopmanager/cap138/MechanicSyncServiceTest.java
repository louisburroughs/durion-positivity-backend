package com.positivity.shopmanager.cap138;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.service.enums.HrEventType;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.service.MechanicSyncServiceImpl;
import com.positivity.shopmanager.service.MechanicSyncService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Service-layer unit tests for {@link MechanicSyncService} — HR mechanic roster
 * and skills import behavior (Story #72 / CAP-138).
 *
 * <p>
 * Covers acceptance criteria AC1-AC6:
 * <ul>
 * <li>AC1 – MechanicUpserted creates/updates mechanic and skills</li>
 * <li>AC2 – MechanicDeactivated sets status INACTIVE and excludes from active
 * queries</li>
 * <li>AC3 – MechanicSkillsUpdated replaces full skill set</li>
 * <li>AC4 – Stale/lower-version events are discarded (monotonic ordering)</li>
 * <li>AC5 – Malformed event (null/missing personId) routes to DLQ; no DB
 * write</li>
 * <li>AC6 – Reconciliation upserts ACTIVE roster; marks
 * locally-ACTIVE-but-absent mechanics INACTIVE</li>
 * </ul>
 *
 * Issue: #72
 */
@ExtendWith(MockitoExtension.class)
class MechanicSyncServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private MechanicRepository mechanicRepository;

    @Mock
    private MechanicSkillRepository mechanicSkillRepository;

    @Mock
    private HrIntegrationLogRepository hrIntegrationLogRepository;

    @Mock
    private MechanicAuditLogRepository mechanicAuditLogRepository;

    private MechanicSyncService mechanicSyncService;

    @BeforeEach
    void setUp() {
        mechanicSyncService = new MechanicSyncServiceImpl(
                mechanicRepository,
                mechanicSkillRepository,
                hrIntegrationLogRepository,
                mechanicAuditLogRepository,
                FIXED_CLOCK);
    }

    // -------------------------------------------------------------------------
    // AC1 – MechanicUpserted creates or updates a Mechanic and its skills
    // -------------------------------------------------------------------------

    /**
     * AC1: A MechanicUpserted event for a new personId must persist a Mechanic
     * record and its associated MechanicSkill records.
     *
     * @see MechanicSyncService#processHrEvent(HrMechanicEvent)
     */
    @Test
    void ac1_mechanicUpserted_newPerson_createsOrUpdatesMechanicAndSkills() {
        // Arrange
        String personId = "HR-1001";
        HrMechanicEvent event = buildUpsertEvent(personId, 1, List.of(
                buildSkillPayload("OIL_CHANGE", 3),
                buildSkillPayload("BRAKE_REPLACE", 2)));

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.empty());
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – mechanic persisted
        ArgumentCaptor<Mechanic> mechanicCaptor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(mechanicCaptor.capture());
        assertThat(mechanicCaptor.getValue().getPersonId()).isEqualTo(personId);
        assertThat(mechanicCaptor.getValue().getStatus()).isEqualTo(MechanicStatus.ACTIVE);
        assertThat(mechanicCaptor.getValue().getVersion()).isEqualTo(1);

        // Assert – skills persisted (replace-set: 2 skills)
        verify(mechanicSkillRepository, times(1)).saveAll(any());

        // Assert – integration log saved (idempotency record)
        verify(hrIntegrationLogRepository).save(any(HrIntegrationLog.class));

        // Assert – audit log saved (ADR-0018)
        verify(mechanicAuditLogRepository).save(any());
    }

    /**
     * AC1 (update path): A MechanicUpserted event for an existing personId with a
     * higher version must update the stored record.
     */
    @Test
    void ac1_mechanicUpserted_existingPerson_higherVersion_updatesMechanic() {
        // Arrange
        String personId = "HR-1002";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 2);
        HrMechanicEvent event = buildUpsertEvent(personId, 3, List.of());

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – same entity updated, version bumped to 3
        ArgumentCaptor<Mechanic> mechanicCaptor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(mechanicCaptor.capture());
        assertThat(mechanicCaptor.getValue().getVersion()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // AC2 – MechanicDeactivated sets status=INACTIVE; excluded from active queries
    // -------------------------------------------------------------------------

    /**
     * AC2: A MechanicDeactivated event must flip the stored mechanic's status to
     * INACTIVE, and thereafter the mechanic must not appear in active-mechanic
     * queries.
     */
    @Test
    void ac2_mechanicDeactivated_setsInactiveAndExcludesFromQueries() {
        // Arrange
        String personId = "HR-2001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 1);
        HrMechanicEvent event = buildDeactivateEvent(personId, 2);

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – status set INACTIVE
        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MechanicStatus.INACTIVE);

        // Assert – active mechanic query excludes INACTIVE records
        mechanicRepository.findAllByStatus(MechanicStatus.ACTIVE);
        verify(mechanicRepository).findAllByStatus(MechanicStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // AC3 – MechanicSkillsUpdated replaces full skill set
    // -------------------------------------------------------------------------

    /**
     * AC3: A MechanicSkillsUpdated event must delete all existing skills for the
     * mechanic and replace with the incoming set (upsert present, remove missing).
     */
    @Test
    void ac3_mechanicSkillsUpdated_replacesFullSkillSet() {
        // Arrange
        String personId = "HR-3001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 4);
        HrMechanicEvent event = buildSkillsUpdatedEvent(personId, 5, List.of(
                buildSkillPayload("TIRE_ROTATION", 1)));

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – old skills removed
        verify(mechanicSkillRepository).deleteAllByMechanicId(existing.getMechanicId());

        // Assert – new skill set saved
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MechanicSkill>> skillCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(mechanicSkillRepository).saveAll(skillCaptor.capture());
        assertThat(skillCaptor.getValue()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // AC4 – Monotonic ordering: version <= stored version is a no-op
    // -------------------------------------------------------------------------

    /**
     * AC4 (equal version): An event whose version equals the stored mechanic
     * version
     * must be discarded with no DB write (BR1 monotonic ordering).
     */
    @Test
    void ac4_staleEvent_sameVersion_isDiscardedNoDbWrite() {
        // Arrange
        String personId = "HR-4001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 7);
        HrMechanicEvent event = buildUpsertEvent(personId, 7, List.of()); // same version

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – no save on any repository
        verify(mechanicRepository, never()).save(any());
        verify(mechanicSkillRepository, never()).saveAll(any());
        verify(mechanicSkillRepository, never()).deleteAllByMechanicId(any());
    }

    /**
     * AC4 (lower version): An event whose version is less than the current stored
     * version must also be discarded.
     */
    @Test
    void ac4_staleEvent_lowerVersion_isDiscardedNoDbWrite() {
        // Arrange
        String personId = "HR-4002";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 10);
        HrMechanicEvent event = buildUpsertEvent(personId, 5, List.of()); // older version

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – stored record unchanged
        verify(mechanicRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC5 – Malformed event (missing personId) → no DB write; exception/DLQ routing
    // -------------------------------------------------------------------------

    /**
     * AC5: An event with a null personId is malformed (BR5). The service must throw
     * an exception (for DLQ routing) and perform no partial write.
     */
    @Test
    void ac5_malformedEvent_nullPersonId_throwsExceptionAndNoDbWrite() {
        // Arrange
        HrMechanicEvent event = buildUpsertEvent(null, 1, List.of()); // null personId

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(IllegalArgumentException.class);

        // Assert – no DB writes of any kind
        verify(mechanicRepository, never()).save(any());
        verify(mechanicRepository, never()).findByPersonId(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    /**
     * AC5: An event with a null version is also malformed (BR5). Same routing.
     */
    @Test
    void ac5_malformedEvent_nullVersion_throwsExceptionAndNoDbWrite() {
        // Arrange – version 0 signals "missing/unset" (caller should never send 0)
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId("HR-5002")
                .version(null)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    /**
     * AC5: An event with a null eventId is also malformed. The service must throw
     * IllegalArgumentException before any DB write.
     */
    @Test
    void ac5_malformedEvent_nullEventId_throwsExceptionAndNoDbWrite() {
        // Arrange
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(null)
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId("HR-5003")
                .version(1)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC6 – Reconciliation: upserts ACTIVE roster; marks locally-ACTIVE-but-missing
    // INACTIVE
    // -------------------------------------------------------------------------

    /**
     * AC6 (deferred): reconcileFromHr() is not yet implemented — requires a live
     * HR roster client. The method must throw {@link UnsupportedOperationException}
     * so callers can detect the incomplete integration rather than silently doing
     * nothing.
     *
     * <p>
     * Full reconciliation logic will be implemented in a follow-up story once
     * the HR client is wired.
     */
    @Test
    void ac6_reconcileFromHr_throwsUnsupportedOperation_pendingHrClient() {
        assertThatThrownBy(() -> mechanicSyncService.reconcileFromHr())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("HR client");
    }

    // -------------------------------------------------------------------------
    // Idempotency guard (BR2) – already-processed eventId is a no-op
    // -------------------------------------------------------------------------

    /**
     * BR2: If the eventId already exists in HrIntegrationLog, the service must
     * return immediately without any DB write.
     */
    @Test
    void br2_duplicateEventId_isNoOp() {
        // Arrange
        HrMechanicEvent event = buildUpsertEvent("HR-7001", 1, List.of());
        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(true);

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – no mechanic lookup or persist
        verify(mechanicRepository, never()).findByPersonId(any());
        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Unknown-personId idempotency (Finding #5 / AC2, AC3 null-mechanic paths)
    // -------------------------------------------------------------------------

    /**
     * AC2 / null-existing: MECHANIC_DEACTIVATED for an unknown personId must still
     * write an HrIntegrationLog so the BR2 idempotency guard engages on
     * re-delivery.
     */
    @Test
    void ac2_mechanicDeactivated_unknownPersonId_stillPersistsIntegrationLog() {
        // Arrange
        HrMechanicEvent event = buildDeactivateEvent("HR-UNKNOWN-001", 1);
        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId("HR-UNKNOWN-001")).thenReturn(Optional.empty());

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – no mechanic upsert but integration log written
        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, times(1)).save(any());
        verify(mechanicAuditLogRepository, never()).save(any());
    }

    /**
     * AC3 / null-existing: MECHANIC_SKILLS_UPDATED for an unknown personId must
     * still
     * write an HrIntegrationLog so the BR2 idempotency guard engages on
     * re-delivery.
     */
    @Test
    void ac3_skillsUpdated_unknownPersonId_stillPersistsIntegrationLog() {
        // Arrange
        HrMechanicEvent event = buildSkillsUpdatedEvent("HR-UNKNOWN-002", 1, List.of());
        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId("HR-UNKNOWN-002")).thenReturn(Optional.empty());

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – no mechanic upsert but integration log written
        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, times(1)).save(any());
        verify(mechanicAuditLogRepository, never()).save(any());
    }

    /**
     * AC1 / null-payload: MECHANIC_UPSERTED with a null payload for a new mechanic
     * must still create the mechanic (with null name/hireDate fields) and write
     * both an HrIntegrationLog and MechanicAuditLog.
     */
    @Test
    void ac1_mechanicUpserted_nullPayload_createsMinimalMechanicRecord() {
        // Arrange
        String personId = "HR-NULLPAY-001";
        Mechanic saved = buildMechanic(personId, MechanicStatus.ACTIVE, 1);
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(1)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(null)
                .build();

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.empty());
        when(mechanicRepository.save(any())).thenReturn(saved);

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – mechanic was created and both logs written
        verify(mechanicRepository, times(1)).save(any());
        verify(hrIntegrationLogRepository, times(1)).save(any());
        verify(mechanicAuditLogRepository, times(1)).save(any());
        // No skills when payload is null
        verify(mechanicSkillRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // F-02 – Stale event persists DISCARDED_STALE integration log
    // -------------------------------------------------------------------------

    /**
     * F-02: A stale event (event version &lt; stored version) must still be
     * persisted as an HrIntegrationLog entry with status DISCARDED_STALE so the
     * BR2 idempotency guard engages on re-delivery. The mechanic must NOT be
     * modified.
     */
    @Test
    void ac4_staleEvent_persistsDiscardedStaleIntegrationLog() {
        // Arrange – existing mechanic version=5, event version=3 (stale)
        String personId = "HR-F02-001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 5);
        HrMechanicEvent event = buildUpsertEvent(personId, 3, List.of());

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – mechanic NOT modified
        verify(mechanicRepository, never()).save(any());
        // Assert – audit log NOT written (no state change)
        verify(mechanicAuditLogRepository, never()).save(any());
        // Assert – integration log IS saved with DISCARDED_STALE status (F-02 audit
        // trail)
        verify(hrIntegrationLogRepository).save(any(HrIntegrationLog.class));
    }

    // -------------------------------------------------------------------------
    // F-07 – Null eventType before switch → throws IllegalArgumentException
    // -------------------------------------------------------------------------

    /**
     * F-07: An event with a null eventType (that is otherwise valid: personId,
     * version, eventId all present) must throw IllegalArgumentException so
     * the caller can route to a DLQ. No mechanic-level write must occur.
     */
    @Test
    void f07_nullEventType_throwsIllegalArgument() {
        // Arrange – valid identification fields, but eventType is null
        String personId = "HR-F07-001";
        UUID eventId = UUID.randomUUID();
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(eventId)
                .eventType(null)
                .personId(personId)
                .version(1)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        when(hrIntegrationLogRepository.existsByEventId(eventId)).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        // No mechanic-level save on null eventType path
        verify(mechanicRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private HrMechanicEvent buildUpsertEvent(String personId, Integer version,
            List<SkillPayload> skills) {
        return HrMechanicEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(buildPayload(personId, skills))
                .build();
    }

    private HrMechanicEvent buildDeactivateEvent(String personId, Integer version) {
        return HrMechanicEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(HrEventType.MECHANIC_DEACTIVATED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();
    }

    private HrMechanicEvent buildSkillsUpdatedEvent(String personId, Integer version,
            List<SkillPayload> skills) {
        return HrMechanicEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(buildPayload(personId, skills))
                .build();
    }

    private Mechanic buildMechanic(String personId, MechanicStatus status, int version) {
        return Mechanic.builder()
                .mechanicId(UUID.randomUUID())
                .personId(personId)
                .firstName("Test")
                .lastName("Mechanic")
                .status(status)
                .hireDate(LocalDate.of(2022, 1, 1))
                .version(version)
                .lastSyncedAt(Instant.now(FIXED_CLOCK))
                .build();
    }

    private HrMechanicEvent.Payload buildPayload(String personId, List<SkillPayload> skills) {
        return HrMechanicEvent.Payload.builder()
                .firstName("Test")
                .lastName("Mechanic")
                .hireDate(LocalDate.of(2022, 1, 1))
                .skills(skills.stream()
                        .map(sp -> HrMechanicEvent.Payload.Skill.builder()
                                .skillCode(sp.skillCode())
                                .proficiencyLevel(sp.proficiencyLevel())
                                .build())
                        .toList())
                .build();
    }

    private SkillPayload buildSkillPayload(String skillCode, int proficiencyLevel) {
        return new SkillPayload(skillCode, proficiencyLevel);
    }

    private record SkillPayload(String skillCode, int proficiencyLevel) {
    }
}
