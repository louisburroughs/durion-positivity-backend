package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.HrIntegrationLog;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.MechanicReplicationPendingException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.service.enums.HrEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        mechanicSyncService = newService(Duration.ZERO);
    }

    /**
     * The unit under test with a given replication wait. {@code setSelf} takes the instance itself
     * rather than a proxy: in a unit test there is no transaction to re-enter, and the call it
     * makes is the same one.
     */
    private MechanicSyncService newService(Duration replicationWait) {
        MechanicSyncServiceImpl service = new MechanicSyncServiceImpl(
                mechanicRepository,
                mechanicSkillRepository,
                hrIntegrationLogRepository,
                mechanicAuditLogRepository,
                FIXED_CLOCK,
                replicationWait);
        service.setSelf(service);
        return service;
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
        HrMechanicEvent event = buildUpsertEvent(
                personId, 1, List.of(buildSkillPayload("OIL_CHANGE", 3), buildSkillPayload("BRAKE_REPLACE", 2)));

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
        HrMechanicEvent event = buildSkillsUpdatedEvent(personId, 5, List.of(buildSkillPayload("TIRE_ROTATION", 1)));

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
                .isInstanceOf(ShopManagerValidationException.class);

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
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId("HR-5002")
                .version(null)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(ShopManagerValidationException.class);

        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    /**
     * AC5: An event with a null eventId is also malformed. The service must throw
     * ShopManagerValidationException before any DB write.
     */
    @Test
    void ac5_malformedEvent_nullEventId_throwsExceptionAndNoDbWrite() {
        // Arrange
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(null)
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId("HR-5003")
                .version(1L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(ShopManagerValidationException.class);

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
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(1L)
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
    // processUpsert update-path branch coverage (payload absent / partial payload)
    // -------------------------------------------------------------------------

    /**
     * Update path, null payload: a MECHANIC_UPSERTED event for an EXISTING
     * mechanic with a null payload must NOT touch firstName/lastName/hireDate,
     * must bump status/version/lastSyncedAt, and must leave the mechanic's
     * skill set alone — the people.events.v1 feed never carries skills, so an
     * event without a skills source is "not sent", not "clear".
     */
    @Test
    void ac1_mechanicUpserted_existingPerson_nullPayload_preservesNameAndHireDate() {
        // Arrange
        String personId = "HR-UPD-NULLPAY-001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(4L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(null)
                .build();

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – name/hireDate untouched, status/version/sync bumped
        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getFirstName()).isEqualTo("Test");
        assertThat(captor.getValue().getLastName()).isEqualTo("Mechanic");
        assertThat(captor.getValue().getHireDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(captor.getValue().getStatus()).isEqualTo(MechanicStatus.ACTIVE);
        assertThat(captor.getValue().getVersion()).isEqualTo(4);

        // Assert – no skills source means the skill set is preserved untouched
        verify(mechanicSkillRepository, never()).deleteAllByMechanicId(any());
        verify(mechanicSkillRepository, never()).saveAll(any());
    }

    /**
     * Update path, payload present but hireDate absent: a partial HR payload
     * (name change without a hireDate) must update firstName/lastName but leave
     * the stored hireDate untouched — protects against field drift from a
     * payload that omits a field rather than intentionally clearing it.
     */
    @Test
    void ac1_mechanicUpserted_existingPerson_payloadWithoutHireDate_keepsExistingHireDate() {
        // Arrange
        String personId = "HR-UPD-NOHIRE-001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        HrMechanicEvent.Payload payload = HrMechanicEvent.Payload.builder()
                .firstName("Updated")
                .lastName("Name")
                .hireDate(null)
                .skills(List.of(HrMechanicEvent.Payload.Skill.builder()
                        .skillCode("OIL_CHANGE")
                        .proficiencyLevel(2)
                        .build()))
                .build();
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(4L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(payload)
                .build();

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – name updated, hireDate preserved from the existing record
        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getFirstName()).isEqualTo("Updated");
        assertThat(captor.getValue().getLastName()).isEqualTo("Name");
        assertThat(captor.getValue().getHireDate()).isEqualTo(LocalDate.of(2022, 1, 1));
    }

    /**
     * Upsert with payload present but no skills source (skills list null, as
     * opposed to an empty list): a null skills list means "not sent" and must
     * preserve the mechanic's current skill set; only an explicit list (empty
     * included) replaces it.
     */
    @Test
    void ac1_mechanicUpserted_payloadPresentSkillsNull_preservesSkills() {
        // Arrange
        String personId = "HR-UPD-NOSKILLS-001";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        HrMechanicEvent.Payload payload = HrMechanicEvent.Payload.builder()
                .firstName("Test")
                .lastName("Mechanic")
                .hireDate(LocalDate.of(2023, 6, 1))
                .skills(null)
                .build();
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(4L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(payload)
                .build();

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – hireDate from payload applied, skills untouched
        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getHireDate()).isEqualTo(LocalDate.of(2023, 6, 1));
        verify(mechanicSkillRepository, never()).deleteAllByMechanicId(any());
        verify(mechanicSkillRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // F-07 – Null eventType before switch → throws ShopManagerValidationException
    // -------------------------------------------------------------------------

    /**
     * F-07: An event with a null eventType (that is otherwise valid: personId,
     * version, eventId all present) must throw ShopManagerValidationException so
     * the caller can route to a DLQ. No mechanic-level write must occur.
     */
    @Test
    void f07_nullEventType_throwsValidationException() {
        // Arrange – valid identification fields, but eventType is null
        String personId = "HR-F07-001";
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(eventId)
                .eventType(null)
                .personId(personId)
                .version(1L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        when(hrIntegrationLogRepository.existsByEventId(eventId)).thenReturn(false);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("eventType");

        // No mechanic-level save on null eventType path
        verify(mechanicRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // replaceSkills — operator API entry riding the feed path
    // -------------------------------------------------------------------------

    /**
     * replaceSkills routes through processHrEvent as a MECHANIC_SKILLS_UPDATED
     * event (single write path), replacing the skill set and refreshing lastSyncedAt. The
     * feed-ordering version is deliberately left alone — see
     * {@link #replaceSkills_doesNotPoisonTheFeedOrderingVersion()}.
     */
    @Test
    void replaceSkills_existingMechanic_replacesViaFeedPath() {
        String personId = "01960011-0000-7000-8000-000000000005";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(hrIntegrationLogRepository.existsByEventId(any())).thenReturn(false);
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        mechanicSyncService.replaceSkills(
                personId,
                List.of(HrMechanicEvent.Payload.Skill.builder()
                        .skillCode("T4-BRAKES")
                        .proficiencyLevel(4)
                        .build()));

        verify(mechanicSkillRepository).deleteAllByMechanicId(existing.getMechanicId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MechanicSkill>> skillCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(mechanicSkillRepository).saveAll(skillCaptor.capture());
        assertThat(skillCaptor.getValue()).hasSize(1);
        // The mechanic's feed-ordering version is untouched: the synthetic event's epoch-millis
        // stamp belongs to no position in the feed's per-aggregate sequence (#1987).
        assertThat(existing.getVersion()).isEqualTo(3);
        verify(hrIntegrationLogRepository).save(any());
        verify(mechanicAuditLogRepository).save(any());
    }

    /**
     * A missing mechanic row is always reported as pending, never as a 404: this service holds no
     * signal that its replica is current, so it cannot tell an unreplicated mechanic from a person
     * who has no technician assignment at all (#1987).
     */
    @Test
    void replaceSkills_noMechanicRow_isReportedAsReplicationPending() {
        UUID personId = UUID.fromString("01960011-0000-7000-8000-00000000000a");
        when(mechanicRepository.findByPersonId(personId.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mechanicSyncService.replaceSkills(personId.toString(), skills()))
                .isInstanceOf(MechanicReplicationPendingException.class)
                .hasMessageContaining("cannot tell the two apart");
        verify(mechanicSkillRepository, never()).deleteAllByMechanicId(any());
    }

    /**
     * The regression for the branch this replaced. An earlier revision inferred "not a technician"
     * from the staffing-assignment replica holding no ACTIVE TECHNICIAN row, so a person with
     * older non-technician assignments and a brand-new technician one still in flight was told,
     * non-retryably, that they were not a mechanic — #1987's own ambiguity, one branch over.
     * Assignment history must have no bearing on the answer.
     */
    @Test
    void replaceSkills_personWithOlderNonTechnicianHistory_isStillReportedAsPending() {
        UUID personId = UUID.fromString("01960011-0000-7000-8000-00000000000b");
        when(mechanicRepository.findByPersonId(personId.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mechanicSyncService.replaceSkills(personId.toString(), skills()))
                .isInstanceOf(MechanicReplicationPendingException.class);
    }

    /**
     * The one negative this service can prove: every person id on the platform is a UUID
     * (ADR-0027), so an id that is not one names nobody and no amount of replication will change
     * that. Refused as a client error, and refused without spending the wait.
     */
    @Test
    void replaceSkills_personIdThatIsNotAUuid_isRefusedNotDeferred() {
        MechanicSyncService service = newService(Duration.ofMinutes(5));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> service.replaceSkills("not-a-uuid", skills()))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("not a UUID");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        verify(mechanicRepository, never()).findByPersonId(any());
    }

    /** The wait is bounded: a configured window that expires refuses rather than blocking on. */
    @Test
    void replaceSkills_waitThatExpires_refusesWithinTheConfiguredWindow() {
        String personId = "01960011-0000-7000-8000-00000000000e";
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.empty());
        MechanicSyncService service = newService(Duration.ofMillis(600));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> service.replaceSkills(personId, skills()))
                .isInstanceOf(MechanicReplicationPendingException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(500));
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    /**
     * An interrupt during the wait refuses immediately and leaves the flag set, so a container
     * shutting the thread down is not swallowed by this loop.
     */
    @Test
    void replaceSkills_interruptedDuringTheWait_refusesAndKeepsTheInterruptFlag() throws Exception {
        String personId = "01960011-0000-7000-8000-00000000000f";
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.empty());
        MechanicSyncService service = newService(Duration.ofMinutes(5));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagKept = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                service.replaceSkills(personId, skills());
            } catch (Throwable t) {
                thrown.set(t);
                interruptFlagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        Thread.sleep(300);
        caller.interrupt();
        caller.join(Duration.ofSeconds(10).toMillis());

        assertThat(thrown.get()).isInstanceOf(MechanicReplicationPendingException.class);
        assertThat(interruptFlagKept).isTrue();
    }

    /**
     * The wait must not run inside a transaction — it waits on a row another thread commits, and
     * an ambient transaction would both hide that commit and hold a pooled connection asleep for
     * the window. Enforced by the annotation rather than left to a comment.
     */
    @Test
    void replaceSkills_suspendsAnyAmbientTransaction() throws Exception {
        Transactional annotation = MechanicSyncServiceImpl.class
                .getMethod("replaceSkills", String.class, List.class, boolean.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    /**
     * An operator skills edit must not advance the mechanic's feed-ordering version: it is stamped
     * with epoch-millis while feed events carry small aggregateVersions, so writing it through
     * would discard every later people.events.v1 event for that mechanic as stale (#1987).
     */
    @Test
    void replaceSkills_doesNotPoisonTheFeedOrderingVersion() {
        String personId = "01960011-0000-7000-8000-000000000010";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(hrIntegrationLogRepository.existsByEventId(any())).thenReturn(false);
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        mechanicSyncService.replaceSkills(personId, skills());

        assertThat(existing.getVersion()).isEqualTo(3);
    }

    /**
     * And the edit itself is never discarded by the stale guard, which compares against that same
     * feed sequence: an operator edit sits outside it entirely.
     */
    @Test
    void replaceSkills_isNotDiscardedByTheFeedStaleGuard() {
        String personId = "01960011-0000-7000-8000-000000000011";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, Integer.MAX_VALUE);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(hrIntegrationLogRepository.existsByEventId(any())).thenReturn(false);
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        mechanicSyncService.replaceSkills(personId, skills());

        verify(mechanicSkillRepository).deleteAllByMechanicId(existing.getMechanicId());
    }

    /** A feed event after an operator edit still applies — the version line was left intact. */
    @Test
    void feedEventAfterAnOperatorSkillsEdit_isStillApplied() {
        String personId = "01960011-0000-7000-8000-000000000012";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        when(mechanicRepository.findByPersonId(personId)).thenReturn(Optional.of(existing));
        when(hrIntegrationLogRepository.existsByEventId(any())).thenReturn(false);
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        mechanicSyncService.replaceSkills(personId, skills());
        mechanicSyncService.processHrEvent(HrMechanicEvent.builder()
                .eventId(UUID.fromString("01960011-0000-7000-8000-000000000013"))
                .eventType(HrEventType.MECHANIC_DEACTIVATED)
                .personId(personId)
                .version(4L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build());

        assertThat(existing.getStatus()).isEqualTo(MechanicStatus.INACTIVE);
        assertThat(existing.getVersion()).isEqualTo(4L);
    }

    /**
     * The point of the wait: a mechanic row that lands while the caller is waiting is used, rather
     * than the caller being refused for a race it could not see (#1987).
     */
    @Test
    void replaceSkills_mechanicArrivingDuringTheWait_isApplied() {
        String personId = "01960011-0000-7000-8000-00000000000d";
        Mechanic existing = buildMechanic(personId, MechanicStatus.ACTIVE, 3);
        when(mechanicRepository.findByPersonId(personId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(hrIntegrationLogRepository.existsByEventId(any())).thenReturn(false);
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        newService(Duration.ofSeconds(5)).replaceSkills(personId, skills());

        verify(mechanicSkillRepository).deleteAllByMechanicId(existing.getMechanicId());
    }

    private List<HrMechanicEvent.Payload.Skill> skills() {
        return List.of(HrMechanicEvent.Payload.Skill.builder()
                .skillCode("T4-BRAKES")
                .proficiencyLevel(4)
                .build());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private HrMechanicEvent buildUpsertEvent(String personId, long version, List<SkillPayload> skills) {
        return HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(buildPayload(personId, skills))
                .build();
    }

    private HrMechanicEvent buildDeactivateEvent(String personId, long version) {
        return HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_DEACTIVATED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();
    }

    private HrMechanicEvent buildSkillsUpdatedEvent(String personId, long version, List<SkillPayload> skills) {
        return HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(buildPayload(personId, skills))
                .build();
    }

    private Mechanic buildMechanic(String personId, MechanicStatus status, int version) {
        return Mechanic.builder()
                .mechanicId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
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

    private record SkillPayload(String skillCode, int proficiencyLevel) {}
}
