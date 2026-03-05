package com.positivity.shopmanager.cap249;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.Assignment;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.AssignmentMechanicRepository;
import com.positivity.shopmanager.internal.repository.AssignmentRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.service.AssignmentServiceImpl;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CAP-249 Story #10: Appointment Assignment Show —
 * verifies that {@link AssignmentServiceImpl} correctly maps entity fields
 * ({@code notes}, {@code createdAt}, {@code updatedAt}) onto the
 * {@link AssignmentResponse} DTO fields
 * ({@code assignmentNotes}, {@code assignedAt}, {@code lastUpdatedAt}).
 *
 * Issue: #10
 */
@ExtendWith(MockitoExtension.class)
class AssignmentServiceImplStory10Test {

    private static final Instant CREATED = Instant.parse("2026-01-10T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-01-15T14:30:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(CREATED, ZoneId.of("UTC"));

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private MechanicRepository mechanicRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentMechanicRepository assignmentMechanicRepository;

    private AssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssignmentServiceImpl(
                appointmentRepository,
                mechanicRepository,
                assignmentRepository,
                assignmentMechanicRepository,
                FIXED_CLOCK);
    }

    // Issue #10: AC2 — notes field mapped to assignmentNotes

    /**
     * Verifies AC2: when {@code Assignment.notes} is populated, the response
     * {@code assignmentNotes} field carries that value verbatim.
     */
    @Test
    void getByAppointmentId_mapsNotesToAssignmentNotes() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Assignment assignment = baseAssignment(assignmentId, appointmentId)
                .notes("Test note")
                .build();

        when(assignmentRepository.findByAppointmentId(appointmentId)).thenReturn(List.of(assignment));
        when(assignmentMechanicRepository.findByAssignmentId(assignmentId)).thenReturn(List.of());

        List<AssignmentResponse> results = service.getByAppointmentId(appointmentId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAssignmentNotes()).isEqualTo("Test note");
    }

    // Issue #10: AC3 — createdAt mapped to assignedAt

    /**
     * Verifies AC3: {@code assignedAt} in the response reflects the assignment's
     * {@code createdAt} timestamp.
     */
    @Test
    void getByAppointmentId_mapsCreatedAtToAssignedAt() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Assignment assignment = baseAssignment(assignmentId, appointmentId)
                .createdAt(CREATED)
                .updatedAt(UPDATED)
                .build();

        when(assignmentRepository.findByAppointmentId(appointmentId)).thenReturn(List.of(assignment));
        when(assignmentMechanicRepository.findByAssignmentId(assignmentId)).thenReturn(List.of());

        List<AssignmentResponse> results = service.getByAppointmentId(appointmentId);

        assertThat(results.get(0).getAssignedAt()).isEqualTo(CREATED);
    }

    // Issue #10: AC3 — updatedAt mapped to lastUpdatedAt

    /**
     * Verifies AC3: {@code lastUpdatedAt} in the response reflects the
     * assignment's {@code updatedAt} timestamp (distinct from {@code createdAt}).
     */
    @Test
    void getByAppointmentId_mapsUpdatedAtToLastUpdatedAt() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Assignment assignment = baseAssignment(assignmentId, appointmentId)
                .createdAt(CREATED)
                .updatedAt(UPDATED)
                .build();

        when(assignmentRepository.findByAppointmentId(appointmentId)).thenReturn(List.of(assignment));
        when(assignmentMechanicRepository.findByAssignmentId(assignmentId)).thenReturn(List.of());

        List<AssignmentResponse> results = service.getByAppointmentId(appointmentId);

        assertThat(results.get(0).getLastUpdatedAt()).isEqualTo(UPDATED);
    }

    // Issue #10: AC2 edge case — null notes

    /**
     * Verifies AC2 edge case: when {@code Assignment.notes} is null,
     * {@code assignmentNotes} in the response is also null (no defaulting).
     */
    @Test
    void getByAppointmentId_nullNotesReturnedAsNull() {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Assignment assignment = baseAssignment(assignmentId, appointmentId)
                .notes(null)
                .build();

        when(assignmentRepository.findByAppointmentId(appointmentId)).thenReturn(List.of(assignment));
        when(assignmentMechanicRepository.findByAssignmentId(assignmentId)).thenReturn(List.of());

        List<AssignmentResponse> results = service.getByAppointmentId(appointmentId);

        assertThat(results.get(0).getAssignmentNotes()).isNull();
    }

    // ---- helpers ----

    /**
     * Returns a partially-configured {@link Assignment} builder suitable for
     * Story #10 mapping tests. Callers may override individual fields as needed.
     *
     * @param assignmentId  the assignment UUID to embed
     * @param appointmentId the appointment UUID to embed
     * @return a builder pre-populated with stable defaults
     */
    private static Assignment.AssignmentBuilder baseAssignment(UUID assignmentId, UUID appointmentId) {
        return Assignment.builder()
                .assignmentId(assignmentId)
                .appointmentId(appointmentId)
                .status(AssignmentStatusEnum.CONFIRMED)
                .version(1)
                .createdAt(CREATED)
                .updatedAt(CREATED);
    }
}
