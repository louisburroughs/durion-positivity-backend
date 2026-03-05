package com.positivity.shopmanager.cap140;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.StatusTimelineEntry;
import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import com.positivity.shopmanager.internal.service.WorkorderStatusEventServiceImpl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for WorkorderStatusEventServiceImpl — CAP-140 Story #63.
 *
 * <p>
 * Verifies event-driven appointment status updates from workexec domain events,
 * covering status mapping, idempotency (duplicate sourceEventId), orphaned
 * workorder
 * detection, unknown status handling, and reopenFlag toggling.
 *
 * <p>
 * Strictness is LENIENT so that stubs set up for GREEN behaviour do not produce
 * UnnecessaryStubbingException while the impl throws
 * UnsupportedOperationException.
 *
 * Issue: #63
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkorderStatusEventServiceTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private AppointmentRepository appointmentRepository;

        @Mock
        private WorkOrderAppointmentMappingRepository mappingRepository;

        @InjectMocks
        private WorkorderStatusEventServiceImpl service;

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        private Appointment buildAppointment(UUID appointmentId, AppointmentStatus status) {
                Instant now = Instant.now(TEST_CLOCK);
                return Appointment.builder()
                                .appointmentId(appointmentId)
                                .status(status)
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .crmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .crmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .startAt(now)
                                .endAt(now.plusSeconds(3600))
                                .statusTimeline(new ArrayList<>())
                                .build();
        }

        private WorkOrderAppointmentMapping buildMapping(UUID workOrderId, UUID appointmentId) {
                return WorkOrderAppointmentMapping.builder()
                                .workOrderId(workOrderId)
                                .appointmentId(appointmentId)
                                .build();
        }

        // -------------------------------------------------------------------------
        // AC1 — Known status maps correctly; appointment updated + timeline entry
        // appended
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_knownStatus_updatesAppointmentStatusAndAppendsTimelineEntry() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Instant eventTimestamp = Instant.now(TEST_CLOCK);

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "DRAFT", eventTimestamp,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                when(mappingRepository.findByWorkOrderId(workOrderId))
                                .thenReturn(Optional.of(buildMapping(workOrderId, appointmentId)));
                when(appointmentRepository.findById(appointmentId))
                                .thenReturn(Optional.of(buildAppointment(appointmentId, AppointmentStatus.SCHEDULED)));

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — DRAFT maps to SCHEDULED; one timeline entry added with correct
                // fields
                ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(captor.capture());
                Appointment saved = captor.getValue();
                assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
                assertThat(saved.getStatusTimeline()).hasSize(1);
                StatusTimelineEntry entry = saved.getStatusTimeline().get(0);
                assertThat(entry.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
                assertThat(entry.getSourceEventId()).isEqualTo(eventId);
                assertThat(entry.getChangeTimestamp()).isNotNull();
        }

        // -------------------------------------------------------------------------
        // AC2 — Duplicate sourceEventId → idempotent; appointment NOT modified
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_duplicateSourceEventId_isIdempotentAndSkipsSave() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Instant now = Instant.now(TEST_CLOCK);

                StatusTimelineEntry existingEntry = StatusTimelineEntry.builder()
                                .status(AppointmentStatus.SCHEDULED)
                                .changeTimestamp(now.minusSeconds(60))
                                .sourceEventId(eventId) // same event already processed
                                .build();

                Appointment appointment = Appointment.builder()
                                .appointmentId(appointmentId)
                                .status(AppointmentStatus.SCHEDULED)
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .crmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .crmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .startAt(now)
                                .endAt(now.plusSeconds(3600))
                                .statusTimeline(new ArrayList<>(List.of(existingEntry)))
                                .build();

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "DRAFT", now,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                when(mappingRepository.findByWorkOrderId(workOrderId))
                                .thenReturn(Optional.of(buildMapping(workOrderId, appointmentId)));
                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — duplicate event: save must never be called
                verify(appointmentRepository, never()).save(any());
        }

        // -------------------------------------------------------------------------
        // AC3 — WorkOrderAppointmentMapping not found → orphan alert; no modification
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_mappingNotFound_treatsAsOrphanAndSkipsSave() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "DRAFT", Instant.now(TEST_CLOCK),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                when(mappingRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.empty());

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — no appointment lookup or update when mapping is absent
                verify(appointmentRepository, never()).findById(any());
                verify(appointmentRepository, never()).save(any());
        }

        // -------------------------------------------------------------------------
        // AC4 — Unknown workexec status → unprocessable; no modification
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_unknownWorkexecStatus_skipsProcessingAndDoesNotSave() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "TOTALLY_UNKNOWN_STATUS", Instant.now(TEST_CLOCK),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — unmappable status: appointment must not be saved
                verify(mappingRepository, never()).findByWorkOrderId(any());
                verify(appointmentRepository, never()).save(any());
        }

        // -------------------------------------------------------------------------
        // AC5 — COMPLETED maps to QUALITY_CHECK
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_completedStatus_mapsToQualityCheck() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Instant now = Instant.now(TEST_CLOCK);

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "COMPLETED", now,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                when(mappingRepository.findByWorkOrderId(workOrderId))
                                .thenReturn(Optional.of(buildMapping(workOrderId, appointmentId)));
                when(appointmentRepository.findById(appointmentId))
                                .thenReturn(Optional.of(
                                                buildAppointment(appointmentId, AppointmentStatus.WORK_IN_PROGRESS)));

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — COMPLETED must map to QUALITY_CHECK per spec
                ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(captor.capture());
                assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.QUALITY_CHECK);
        }

        // -------------------------------------------------------------------------
        // AC6 — INVOICED maps to INVOICED
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_invoicedStatus_mapsToInvoiced() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Instant now = Instant.now(TEST_CLOCK);

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "INVOICED", now,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                when(mappingRepository.findByWorkOrderId(workOrderId))
                                .thenReturn(Optional.of(buildMapping(workOrderId, appointmentId)));
                when(appointmentRepository.findById(appointmentId))
                                .thenReturn(Optional
                                                .of(buildAppointment(appointmentId, AppointmentStatus.QUALITY_CHECK)));

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — INVOICED status maps 1:1 to INVOICED
                ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(captor.capture());
                assertThat(captor.getValue().getStatus()).isEqualTo(AppointmentStatus.INVOICED);
        }

        // -------------------------------------------------------------------------
        // AC7 — REOPENED → reopenFlag set permanently to true; status set to REOPENED
        // -------------------------------------------------------------------------

        @Test
        void handleWorkorderStatusChanged_reopenedStatus_setsReopenFlagTrueAndMapsStatus() {
                // Arrange
                UUID workOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Instant now = Instant.now(TEST_CLOCK);

                Appointment appointment = Appointment.builder()
                                .appointmentId(appointmentId)
                                .status(AppointmentStatus.INVOICED)
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .crmCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .crmVehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .startAt(now)
                                .endAt(now.plusSeconds(3600))
                                .statusTimeline(new ArrayList<>())
                                .reopenFlag(false)
                                .build();

                WorkorderStatusChangedEvent event = new WorkorderStatusChangedEvent(
                                eventId, workOrderId, "REOPENED", now,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                when(mappingRepository.findByWorkOrderId(workOrderId))
                                .thenReturn(Optional.of(buildMapping(workOrderId, appointmentId)));
                when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

                // Act
                service.handleWorkorderStatusChanged(event);

                // Assert — reopenFlag must be true and status must be REOPENED
                ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
                verify(appointmentRepository).save(captor.capture());
                Appointment saved = captor.getValue();
                assertThat(saved.isReopenFlag()).isTrue();
                assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.REOPENED);
        }
}
