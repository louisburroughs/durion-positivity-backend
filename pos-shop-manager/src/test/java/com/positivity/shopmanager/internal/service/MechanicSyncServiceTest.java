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
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.HrIntegrationLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicAuditLogRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.service.enums.HrEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * import behavior (Story #72 / CAP-138).
 *
 * <p>
 * Covers acceptance criteria AC1-AC6:
 * <ul>
 * <li>AC1 – MechanicUpserted creates/updates mechanic</li>
 * <li>AC2 – MechanicDeactivated sets status INACTIVE and excludes from active
 * queries</li>
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
    private HrIntegrationLogRepository hrIntegrationLogRepository;

    @Mock
    private MechanicAuditLogRepository mechanicAuditLogRepository;

    private MechanicSyncService mechanicSyncService;

    @BeforeEach
    void setUp() {
        mechanicSyncService = newService();
    }

    private MechanicSyncService newService() {
        return new MechanicSyncServiceImpl(
                mechanicRepository, hrIntegrationLogRepository, mechanicAuditLogRepository, FIXED_CLOCK);
    }

    // -------------------------------------------------------------------------
    // AC1 – MechanicUpserted creates or updates a Mechanic
    // -------------------------------------------------------------------------

    /**
     * AC1: A MechanicUpserted event for a new personId must persist a Mechanic
     * record.
     *
     * @see MechanicSyncService#processHrEvent(HrMechanicEvent)
     */
    @Test
    void ac1_mechanicUpserted_newPerson_createsOrUpdatesMechanicAndSkills() {
        // Arrange
        String personId = "01960011-0000-7000-8000-0000000000a1";
        HrMechanicEvent event = buildUpsertEvent(personId, 1);

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.empty());
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – mechanic persisted
        ArgumentCaptor<Mechanic> mechanicCaptor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(mechanicCaptor.capture());
        assertThat(mechanicCaptor.getValue().getPersonId()).isEqualTo(UUID.fromString(personId));
        assertThat(mechanicCaptor.getValue().getStatus()).isEqualTo(MechanicStatus.ACTIVE);
        assertThat(mechanicCaptor.getValue().getVersion()).isEqualTo(1);

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
        String personId = "01960011-0000-7000-8000-0000000000a2";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 2);
        HrMechanicEvent event = buildUpsertEvent(personId, 3);

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));
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
        String personId = "01960011-0000-7000-8000-0000000000b1";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 1);
        HrMechanicEvent event = buildDeactivateEvent(personId, 2);

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));
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
        String personId = "01960011-0000-7000-8000-0000000000c1";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 7);
        HrMechanicEvent event = buildUpsertEvent(personId, 7); // same version

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – no save on any repository
        verify(mechanicRepository, never()).save(any());
    }

    /**
     * AC4 (lower version): An event whose version is less than the current stored
     * version must also be discarded.
     */
    @Test
    void ac4_staleEvent_lowerVersion_isDiscardedNoDbWrite() {
        // Arrange
        String personId = "01960011-0000-7000-8000-0000000000c2";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 10);
        HrMechanicEvent event = buildUpsertEvent(personId, 5); // older version

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));

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
        HrMechanicEvent event = buildUpsertEvent(null, 1); // null personId

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
        // Arrange – a valid personId so the null-version guard is what actually fires
        String personId = "01960011-0000-7000-8000-0000000000d1";
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
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
        String personId = "01960011-0000-7000-8000-0000000000d2";
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(null)
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(1L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(ShopManagerValidationException.class);

        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    /**
     * The one negative this service can prove: every person id on the platform is a UUID
     * (ADR-0027), so an id that is not one names nobody. It is refused before any repository call,
     * ahead of the version/eventId checks that follow it.
     */
    @Test
    void ac5_malformedEvent_personIdNotAUuid_throwsExceptionAndNoRepositoryCalled() {
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId("HR-5002")
                .version(1L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("not a UUID");

        verify(mechanicRepository, never()).findByPersonId(any());
        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).existsByEventId(any());
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
        HrMechanicEvent event = buildUpsertEvent("01960011-0000-7000-8000-000000000701", 1);
        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(true);

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – no mechanic lookup or persist
        verify(mechanicRepository, never()).findByPersonId(any());
        verify(mechanicRepository, never()).save(any());
        verify(hrIntegrationLogRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Unknown-personId idempotency (Finding #5 / AC2 null-mechanic path)
    // -------------------------------------------------------------------------

    /**
     * AC2 / null-existing: MECHANIC_DEACTIVATED for an unknown personId must still
     * write an HrIntegrationLog so the BR2 idempotency guard engages on
     * re-delivery.
     */
    @Test
    void ac2_mechanicDeactivated_unknownPersonId_stillPersistsIntegrationLog() {
        // Arrange
        String personId = "01960011-0000-7000-8000-000000000801";
        HrMechanicEvent event = buildDeactivateEvent(personId, 1);
        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.empty());

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
        String personId = "01960011-0000-7000-8000-000000000901";
        Mechanic saved = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 1);
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(1L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(null)
                .build();

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.empty());
        when(mechanicRepository.save(any())).thenReturn(saved);

        // Act
        mechanicSyncService.processHrEvent(event);

        // Assert – mechanic was created and both logs written
        verify(mechanicRepository, times(1)).save(any());
        verify(hrIntegrationLogRepository, times(1)).save(any());
        verify(mechanicAuditLogRepository, times(1)).save(any());
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
        String personId = "01960011-0000-7000-8000-000000000b02";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 5);
        HrMechanicEvent event = buildUpsertEvent(personId, 3);

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));

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
     * and must bump status/version/lastSyncedAt.
     */
    @Test
    void ac1_mechanicUpserted_existingPerson_nullPayload_preservesNameAndHireDate() {
        // Arrange
        String personId = "01960011-0000-7000-8000-000000000c02";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 3);
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(4L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(null)
                .build();

        when(hrIntegrationLogRepository.existsByEventId(event.getEventId())).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));
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
        String personId = "01960011-0000-7000-8000-000000000d02";
        Mechanic existing = buildMechanic(UUID.fromString(personId), MechanicStatus.ACTIVE, 3);
        HrMechanicEvent.Payload payload = HrMechanicEvent.Payload.builder()
                .firstName("Updated")
                .lastName("Name")
                .hireDate(null)
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
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.of(existing));
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
        String personId = "01960011-0000-7000-8000-000000000e02";
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        HrMechanicEvent event = HrMechanicEvent.builder()
                .eventId(eventId)
                .eventType(null)
                .personId(personId)
                .version(1L)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .build();

        when(hrIntegrationLogRepository.existsByEventId(eventId)).thenReturn(false);
        when(mechanicRepository.findByPersonId(UUID.fromString(personId))).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> mechanicSyncService.processHrEvent(event))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining("eventType");

        // No mechanic-level save on null eventType path
        verify(mechanicRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private HrMechanicEvent buildUpsertEvent(String personId, long version) {
        return HrMechanicEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(personId)
                .version(version)
                .occurredAt(Instant.now(FIXED_CLOCK))
                .payload(buildPayload())
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

    private Mechanic buildMechanic(UUID personId, MechanicStatus status, int version) {
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

    private HrMechanicEvent.Payload buildPayload() {
        return HrMechanicEvent.Payload.builder()
                .firstName("Test")
                .lastName("Mechanic")
                .hireDate(LocalDate.of(2022, 1, 1))
                .build();
    }
}
