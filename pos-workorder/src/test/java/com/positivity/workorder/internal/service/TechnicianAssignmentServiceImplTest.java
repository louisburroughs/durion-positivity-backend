package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.TechnicianAssignmentRecord;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.TechnicianAlreadyAssignedException;
import com.positivity.workorder.internal.exception.TechnicianNotAssignedException;
import com.positivity.workorder.internal.exception.TechnicianNotFoundException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Technician assignment: assignment is only allowed from APPROVED / ASSIGNED / WORK_IN_PROGRESS,
 * the first assignment from APPROVED drives the workorder to ASSIGNED, and every superseded
 * assignment is retained as history with {@code current} cleared.
 *
 * <p>Since #1985 a workorder has <em>exactly one</em> current technician: assign requires the
 * workorder to be free and refuses otherwise, reassign requires it to be held, and a lost race
 * against the partial unique index answers with the same refusal as the pre-check.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TechnicianAssignmentServiceImpl")
class TechnicianAssignmentServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2211");
    private static final UUID TECHNICIAN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2212");
    private static final UUID OTHER_TECHNICIAN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2213");
    private static final Long ASSIGNMENT_ID = 4711L;
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock
    private TechnicianAssignmentRepository assignmentRepository;

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderStateMachine stateMachine;

    @Mock
    private com.positivity.workorder.internal.repository.ExtPersonReplicaRepository extPersonReplicaRepository;

    private TechnicianAssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TechnicianAssignmentServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                assignmentRepository,
                workorderRepository,
                stateMachine,
                extPersonReplicaRepository);

        // #1983: an assignment now names a technician this module knows from the ext_person replica.
        when(extPersonReplicaRepository.existsById(any())).thenReturn(true);

        when(assignmentRepository.save(any())).thenAnswer(TechnicianAssignmentServiceImplTest::stampId);
        // Assign and reassign write the new current row through saveAndFlush so a lost race against
        // technician_assignment_one_current_uniq surfaces as a 409 rather than a 500 at commit
        // (#1985); reassign also flushes the closed row first, because Hibernate runs inserts before
        // updates and would otherwise present two current rows to the index at once.
        when(assignmentRepository.saveAndFlush(any())).thenAnswer(TechnicianAssignmentServiceImplTest::stampId);
        when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                .thenReturn(Optional.empty());
        // Reassign and release take the current row under a lock so they cannot disagree about which
        // assignment they are ending (#1985); the stub mirrors the unlocked finder.
        when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.empty());
    }

    private static TechnicianAssignment stampId(org.mockito.invocation.InvocationOnMock invocation) {
        TechnicianAssignment assignment = invocation.getArgument(0);
        if (assignment.getId() == null) {
            assignment.setId(ASSIGNMENT_ID);
        }
        return assignment;
    }

    private void givenWorkorder(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setStatus(status);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
    }

    private TechnicianAssignment currentAssignment(UUID technicianId) {
        return TechnicianAssignment.builder()
                .id(ASSIGNMENT_ID)
                .workorder(new Workorder(WORKORDER_ID))
                .technicianId(technicianId)
                .assignedBy("dispatch")
                .assignedAt(NOW_LOCAL.minusHours(3))
                .current(true)
                .build();
    }

    @Nested
    @DisplayName("assignTechnician")
    class AssignTechnician {

        @Test
        @DisplayName("creates a current assignment stamped with the clock")
        void createsAssignment() {
            givenWorkorder(WorkorderStatus.APPROVED);

            TechnicianAssignmentRecord assignment =
                    service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", "morning bay");

            assertThat(assignment.technicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(assignment.assignedBy()).isEqualTo("dispatch");
            assertThat(assignment.assignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(assignment.notes()).isEqualTo("morning bay");
            assertThat(assignment.current()).isTrue();
            assertThat(assignment.workorderId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        @DisplayName("drives an APPROVED workorder to ASSIGNED")
        void transitionsApprovedWorkorder() {
            givenWorkorder(WorkorderStatus.APPROVED);

            service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null);

            verify(stateMachine)
                    .transitionWorkorder(WORKORDER_ID, WorkorderStatus.ASSIGNED, "dispatch", "Technician assigned");
        }

        @Test
        @DisplayName("leaves an already-ASSIGNED workorder in place")
        void doesNotRetransitionAssignedWorkorder() {
            givenWorkorder(WorkorderStatus.ASSIGNED);

            service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null);

            verify(stateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("leaves a WORK_IN_PROGRESS workorder in place")
        void doesNotRetransitionInProgressWorkorder() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null);

            verify(stateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("#1985: refuses a workorder that already has a technician, naming who holds it")
        void refusesWhenAlreadyAssigned() {
            givenWorkorder(WorkorderStatus.ASSIGNED);
            TechnicianAssignment existing = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(existing));
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.of(existing));

            // This used to succeed, silently closing the incumbent's row with a canned reason, which
            // made an accidental double assign indistinguishable from a deliberate hand-over.
            assertThatThrownBy(() -> service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null))
                    .isInstanceOf(TechnicianAlreadyAssignedException.class)
                    .hasMessageContaining(OTHER_TECHNICIAN_ID.toString());

            assertThat(existing.getCurrent()).isTrue();
            assertThat(existing.getUnassignedAt()).isNull();
            verify(assignmentRepository, never()).save(any());
            verify(assignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("#1985: a lost race against the unique index is the same refusal, not a 500")
        void concurrentAssignBecomesConflict() {
            givenWorkorder(WorkorderStatus.ASSIGNED);
            // doThrow, not when(...): when() would call the already-stubbed saveAndFlush with a null
            // argument while setting the stub up, and the setUp answer would dereference it.
            org.mockito.Mockito.doThrow(
                            new DataIntegrityViolationException("duplicate key value violates unique constraint "
                                    + "\"technician_assignment_one_current_uniq\""))
                    .when(assignmentRepository)
                    .saveAndFlush(any());

            // Both requests read "no current technician" — neither sees the other's uncommitted row —
            // so the index is what decides, and the loser must hear the same thing the pre-check says.
            assertThatThrownBy(() -> service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null))
                    .isInstanceOf(TechnicianAlreadyAssignedException.class);
        }

        @Test
        @DisplayName("#1983: a technician the ext_person replica does not know is refused")
        void refusesUnknownTechnician() {
            givenWorkorder(WorkorderStatus.APPROVED);
            when(extPersonReplicaRepository.existsById(TECHNICIAN_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null))
                    .isInstanceOf(TechnicianNotFoundException.class);
            verify(assignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects an unknown workorder")
        void rejectsUnknownWorkorder() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null))
                    .isInstanceOf(WorkorderNotFoundException.class);
        }

        @Test
        @DisplayName("rejects a workorder in a status that does not allow assignment")
        void rejectsIneligibleStatus() {
            givenWorkorder(WorkorderStatus.COMPLETED);

            // #1983: a closed workorder answers with the stable code, not the generic status message.
            assertThatThrownBy(() -> service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null))
                    .isInstanceOf(WorkorderClosedException.class)
                    .hasMessageContaining("COMPLETED");
            verify(assignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reassignTechnician")
    class ReassignTechnician {

        @Test
        @DisplayName("closes the current assignment with the supplied reason and opens a new one")
        void reassigns() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            TechnicianAssignment existing = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(existing));
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.of(existing));

            TechnicianAssignmentRecord created = service.reassignTechnician(
                    WORKORDER_ID, TECHNICIAN_ID, "supervisor", "called out sick", "swap to bay 2");

            assertThat(existing.getCurrent()).isFalse();
            assertThat(existing.getUnassignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(existing.getReassignmentReason()).isEqualTo("called out sick");
            // The closed row is flushed, not merely saved: Hibernate would otherwise insert the new
            // current row before updating the old one and the index would refuse the reassignment.
            verify(assignmentRepository).saveAndFlush(existing);

            assertThat(created.technicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(created.assignedBy()).isEqualTo("supervisor");
            assertThat(created.reassignmentReason()).isEqualTo("called out sick");
            assertThat(created.notes()).isEqualTo("swap to bay 2");
            assertThat(created.current()).isTrue();
        }

        @Test
        @DisplayName("keeps the prior reason when none is supplied")
        void keepsPriorReasonWhenReasonOmitted() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            TechnicianAssignment existing = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(existing));
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.of(existing));

            service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", null, null);

            assertThat(existing.getCurrent()).isFalse();
            assertThat(existing.getReassignmentReason()).isNull();
        }

        @Test
        @DisplayName("never transitions the workorder status")
        void doesNotTransitionWorkorder() {
            givenWorkorder(WorkorderStatus.APPROVED);
            TechnicianAssignment seeded = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(seeded));
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.of(seeded));

            service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", "reason", null);

            verify(stateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses to reassign a workorder that has no current assignment")
        void rejectsWithoutCurrentAssignment() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            assertThatThrownBy(
                            () -> service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", "reason", null))
                    .isInstanceOf(TechnicianNotAssignedException.class)
                    .hasMessageContaining("no current technician assignment");
        }

        @Test
        @DisplayName("rejects an unknown workorder and an ineligible status")
        void rejectsUnresolvableTargets() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(
                            () -> service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", "reason", null))
                    .isInstanceOf(WorkorderNotFoundException.class);

            givenWorkorder(WorkorderStatus.CANCELLED);
            assertThatThrownBy(
                            () -> service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", "reason", null))
                    .isInstanceOf(WorkorderClosedException.class)
                    .hasMessageContaining("CANCELLED");
        }
    }

    @Nested
    @DisplayName("releaseAssignment")
    class ReleaseAssignment {

        @Test
        @DisplayName("#1983: closes the current assignment and records who ended it")
        void releaseRecordsTheActor() {
            TechnicianAssignment existing = currentAssignment(TECHNICIAN_ID);
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.of(existing));

            service.releaseAssignment(WORKORDER_ID, "supervisor", "Shift ended");

            assertThat(existing.getCurrent()).isFalse();
            assertThat(existing.getUnassignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(existing.getReassignmentReason()).isEqualTo("Shift ended");
            // assignedBy answers who put the technician on; without releasedBy nothing answered who
            // took them off, and the story asks for who, when and why.
            assertThat(existing.getReleasedBy()).isEqualTo("supervisor");
        }

        @Test
        @DisplayName("#1985: reads the current row under a lock so a racing reassign cannot be missed")
        void releaseTakesTheRowLock() {
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID))
                    .thenReturn(Optional.of(currentAssignment(TECHNICIAN_ID)));

            service.releaseAssignment(WORKORDER_ID, "supervisor", null);

            verify(assignmentRepository).findCurrentForUpdate(WORKORDER_ID);
            verify(assignmentRepository, never()).findByWorkorder_IdAndCurrentTrue(WORKORDER_ID);
        }

        @Test
        @DisplayName("releasing a workorder with no technician writes nothing")
        void releaseWithoutAssignmentIsEmpty() {
            assertThat(service.releaseAssignment(WORKORDER_ID, "supervisor", null))
                    .isEmpty();
            verify(assignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("requireOpenWorkorder")
    class RequireOpenWorkorder {

        @Test
        @DisplayName("#1983: a closed workorder is refused with the stable code, an open one passes")
        void guardsTheOpenLifecycle() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            service.requireOpenWorkorder(WORKORDER_ID);

            givenWorkorder(WorkorderStatus.COMPLETED);
            assertThatThrownBy(() -> service.requireOpenWorkorder(WORKORDER_ID))
                    .isInstanceOf(WorkorderClosedException.class);
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("exposes the current assignment when one exists")
        void currentAssignmentIsExposed() {
            TechnicianAssignment existing = currentAssignment(TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(existing));
            when(assignmentRepository.findCurrentForUpdate(WORKORDER_ID)).thenReturn(Optional.of(existing));

            assertThat(service.getCurrentAssignment(WORKORDER_ID))
                    .contains(TechnicianAssignmentRecord.fromEntity(existing));
        }

        @Test
        @DisplayName("returns empty when the workorder is unassigned")
        void noCurrentAssignment() {
            assertThat(service.getCurrentAssignment(WORKORDER_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns the assignment history newest first")
        void historyIsDelegated() {
            TechnicianAssignment newest = currentAssignment(TECHNICIAN_ID);
            TechnicianAssignment older = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of(newest, older));

            assertThat(service.getAssignmentHistory(WORKORDER_ID))
                    .containsExactly(
                            TechnicianAssignmentRecord.fromEntity(newest),
                            TechnicianAssignmentRecord.fromEntity(older));
        }

        @Test
        @DisplayName("reads the previous technician from the second-newest assignment")
        void previousTechnicianFromHistory() {
            when(assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of(currentAssignment(TECHNICIAN_ID), currentAssignment(OTHER_TECHNICIAN_ID)));

            assertThat(service.getPreviousTechnicianId(WORKORDER_ID)).contains(OTHER_TECHNICIAN_ID);
        }

        @Test
        @DisplayName("has no previous technician after a single assignment")
        void noPreviousTechnician() {
            when(assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(WORKORDER_ID))
                    .thenReturn(List.of(currentAssignment(TECHNICIAN_ID)));

            assertThat(service.getPreviousTechnicianId(WORKORDER_ID)).isEmpty();
        }

        @Test
        @DisplayName("reports the workorder status, or fails when the workorder is unknown")
        void workorderStatus() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            assertThat(service.getWorkorderStatus(WORKORDER_ID)).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);

            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getWorkorderStatus(WORKORDER_ID))
                    .isInstanceOf(WorkorderNotFoundException.class);
        }
    }
}
