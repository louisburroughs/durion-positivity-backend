package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Technician assignment: assignment is only allowed from APPROVED / ASSIGNED / WORK_IN_PROGRESS,
 * the first assignment from APPROVED drives the workorder to ASSIGNED, and every superseded
 * assignment is retained as history with {@code current} cleared.
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

    private TechnicianAssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TechnicianAssignmentServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC), assignmentRepository, workorderRepository, stateMachine);

        when(assignmentRepository.save(any())).thenAnswer(invocation -> {
            TechnicianAssignment assignment = invocation.getArgument(0);
            if (assignment.getId() == null) {
                assignment.setId(ASSIGNMENT_ID);
            }
            return assignment;
        });
        when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                .thenReturn(Optional.empty());
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

            TechnicianAssignment assignment =
                    service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", "morning bay");

            assertThat(assignment.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(assignment.getAssignedBy()).isEqualTo("dispatch");
            assertThat(assignment.getAssignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(assignment.getNotes()).isEqualTo("morning bay");
            assertThat(assignment.getCurrent()).isTrue();
            assertThat(assignment.getWorkorder().getId()).isEqualTo(WORKORDER_ID);
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
        @DisplayName("supersedes the existing assignment instead of deleting it")
        void supersedesExistingAssignment() {
            givenWorkorder(WorkorderStatus.ASSIGNED);
            TechnicianAssignment existing = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(existing));

            service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null);

            assertThat(existing.getCurrent()).isFalse();
            assertThat(existing.getUnassignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(existing.getReassignmentReason()).isEqualTo("Reassigned to different technician");

            ArgumentCaptor<TechnicianAssignment> captor = ArgumentCaptor.forClass(TechnicianAssignment.class);
            verify(assignmentRepository, org.mockito.Mockito.times(2)).save(captor.capture());
            assertThat(captor.getAllValues().get(0)).isSameAs(existing);
            assertThat(captor.getAllValues().get(1).getTechnicianId()).isEqualTo(TECHNICIAN_ID);
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

            assertThatThrownBy(() -> service.assignTechnician(WORKORDER_ID, TECHNICIAN_ID, "dispatch", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current status: COMPLETED");
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

            TechnicianAssignment created = service.reassignTechnician(
                    WORKORDER_ID, TECHNICIAN_ID, "supervisor", "called out sick", "swap to bay 2");

            assertThat(existing.getCurrent()).isFalse();
            assertThat(existing.getUnassignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(existing.getReassignmentReason()).isEqualTo("called out sick");

            assertThat(created.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(created.getAssignedBy()).isEqualTo("supervisor");
            assertThat(created.getReassignmentReason()).isEqualTo("called out sick");
            assertThat(created.getNotes()).isEqualTo("swap to bay 2");
            assertThat(created.getCurrent()).isTrue();
        }

        @Test
        @DisplayName("keeps the prior reason when none is supplied")
        void keepsPriorReasonWhenReasonOmitted() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            TechnicianAssignment existing = currentAssignment(OTHER_TECHNICIAN_ID);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(existing));

            service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", null, null);

            assertThat(existing.getCurrent()).isFalse();
            assertThat(existing.getReassignmentReason()).isNull();
        }

        @Test
        @DisplayName("never transitions the workorder status")
        void doesNotTransitionWorkorder() {
            givenWorkorder(WorkorderStatus.APPROVED);
            when(assignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(currentAssignment(OTHER_TECHNICIAN_ID)));

            service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", "reason", null);

            verify(stateMachine, never()).transitionWorkorder(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses to reassign a workorder that has no current assignment")
        void rejectsWithoutCurrentAssignment() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            assertThatThrownBy(
                            () -> service.reassignTechnician(WORKORDER_ID, TECHNICIAN_ID, "supervisor", "reason", null))
                    .isInstanceOf(IllegalStateException.class)
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
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current status: CANCELLED");
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

            assertThat(service.getCurrentAssignment(WORKORDER_ID)).contains(existing);
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

            assertThat(service.getAssignmentHistory(WORKORDER_ID)).containsExactly(newest, older);
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
