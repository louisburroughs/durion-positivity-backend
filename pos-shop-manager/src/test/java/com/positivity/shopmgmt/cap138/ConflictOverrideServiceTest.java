package com.positivity.shopmgmt.cap138;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.OverrideRecord;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.OverrideRecordRepository;
import com.positivity.shopmanager.internal.service.ConflictOverrideServiceImpl;
import com.positivity.shopmanager.service.ConflictOverrideService;
import com.positivity.shopmanager.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.service.dto.ConflictOverrideResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class ConflictOverrideServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID APPT_ID = UUID.randomUUID();
    private static final String ACTOR_SYSTEM = "system";

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private OverrideRecordRepository overrideRecordRepository;

    private ConflictOverrideServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConflictOverrideServiceImpl(appointmentRepository, overrideRecordRepository, FIXED_CLOCK);
    }

    // -------------------------------------------------------------------------
    // AC2 – @PreAuthorize secures the service interface
    // -------------------------------------------------------------------------

    /**
     * AC2: The ConflictOverrideService.execute() method MUST declare
     * @PreAuthorize("hasAuthority('shopmgr.appointment.override')") so that
     * Spring Security rejects unauthorized callers with HTTP 403.
     */
    @Test
    void ac2_serviceInterface_declaresShopmgrOverridePreAuthorize() throws Exception {
        var method = ConflictOverrideService.class.getMethod("execute", ConflictOverrideRequest.class);
        assertThat(method.isAnnotationPresent(PreAuthorize.class)).isTrue();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("shopmgr.appointment.override");
    }

    // -------------------------------------------------------------------------
    // AC1 / AC4 – Successful override
    // -------------------------------------------------------------------------

    /**
     * AC1 + AC4: When a valid override request is submitted for a known appointment,
     * the appointment is flagged with isConflictOverride=true, an OverrideRecord is
     * persisted with the actor and timestamp, and the response contains the override
     * audit details including the actor.
     */
    @Test
    void ac1_validOverride_appointmentFlaggedAndRecordCreated() {
        Appointment appointment = buildAppointment(APPT_ID);
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.of(appointment));
        when(overrideRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(appointmentRepository.save(any())).thenReturn(appointment);

        ConflictOverrideRequest request = ConflictOverrideRequest.builder()
                .appointmentId(APPT_ID)
                .overrideReason("Customer waiting on-site — urgent repair")
                .conflictDetails("{\"type\":\"DOUBLE_BOOKED\",\"resourceId\":\"HR-7001\"}")
                .build();

        ConflictOverrideResponse response = service.execute(request);

        // AC1: OverrideRecord persisted with correct actor and timestamp
        ArgumentCaptor<OverrideRecord> recordCaptor = ArgumentCaptor.forClass(OverrideRecord.class);
        verify(overrideRecordRepository, times(1)).save(recordCaptor.capture());
        OverrideRecord saved = recordCaptor.getValue();
        assertThat(saved.getAppointmentId()).isEqualTo(APPT_ID);
        assertThat(saved.getOverrideReason()).isEqualTo("Customer waiting on-site — urgent repair");
        assertThat(saved.getOverrideTimestamp()).isEqualTo(Instant.now(FIXED_CLOCK));
        // ADR-0018: Audit actor must be populated
        assertThat(saved.getOverriddenByUserId()).isEqualTo(ACTOR_SYSTEM);

        // AC4: Appointment flagged
        ArgumentCaptor<Appointment> apptCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository, times(1)).save(apptCaptor.capture());
        assertThat(apptCaptor.getValue().isConflictOverride()).isTrue();

        // Response populated, including actor
        assertThat(response.getAppointmentId()).isEqualTo(APPT_ID);
        assertThat(response.getOverrideReason()).isEqualTo("Customer waiting on-site — urgent repair");
        assertThat(response.getOverriddenByUserId()).isEqualTo(ACTOR_SYSTEM);
    }

    // -------------------------------------------------------------------------
    // AC3 – Missing / blank override reason
    // -------------------------------------------------------------------------

    /**
     * AC3: When the override reason is blank, the service throws
     * IllegalArgumentException before touching any repository.
     */
    @Test
    void ac3_blankReason_throwsIllegalArgument() {
        ConflictOverrideRequest request = ConflictOverrideRequest.builder()
                .appointmentId(APPT_ID)
                .overrideReason("   ")
                .build();

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(appointmentRepository, never()).findById(any());
        verify(overrideRecordRepository, never()).save(any());
    }

    /**
     * AC3 (empty): An empty override reason is also rejected before any repo is touched.
     */
    @Test
    void ac3_emptyReason_throwsIllegalArgument() {
        ConflictOverrideRequest request = ConflictOverrideRequest.builder()
                .appointmentId(APPT_ID)
                .overrideReason("")
                .build();

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(appointmentRepository, never()).findById(any());
        verify(overrideRecordRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Appointment not found
    // -------------------------------------------------------------------------

    /**
     * When the referenced appointment does not exist, the service throws
     * IllegalArgumentException without persisting any record.
     */
    @Test
    void appointmentNotFound_throwsIllegalArgument() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.empty());

        ConflictOverrideRequest request = ConflictOverrideRequest.builder()
                .appointmentId(APPT_ID)
                .overrideReason("Valid reason")
                .build();

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(APPT_ID.toString());

        verify(overrideRecordRepository, never()).save(any());
        verify(appointmentRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private Appointment buildAppointment(UUID appointmentId) {
        return Appointment.builder()
                .appointmentId(appointmentId)
                .locationId(UUID.randomUUID())
                .crmCustomerId(UUID.randomUUID())
                .crmVehicleId(UUID.randomUUID())
                .resourceId("HR-7001")
                .resourceType("MECHANIC")
                .startAt(Instant.parse("2026-06-15T10:00:00Z"))
                .endAt(Instant.parse("2026-06-15T12:00:00Z"))
                .status(AppointmentStatus.SCHEDULED)
                .build();
    }
}
