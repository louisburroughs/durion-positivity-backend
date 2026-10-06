package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderAppointmentLinkService — writing the workorder-to-appointment link (#2531)")
class WorkorderAppointmentLinkServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Mock
    private WorkOrderAppointmentMappingRepository mappingRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @InjectMocks
    private WorkorderAppointmentLinkServiceImpl service;

    @Test
    @DisplayName("an unlinked workorder is linked to the appointment its fact names")
    void linksAnUnlinkedWorkorder() {
        Appointment appointment =
                Appointment.builder().appointmentId(APPOINTMENT_ID).build();
        when(mappingRepository.existsById(WORKORDER_ID)).thenReturn(false);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        assertThat(service.link(WORKORDER_ID, APPOINTMENT_ID)).isTrue();

        ArgumentCaptor<WorkOrderAppointmentMapping> captor = ArgumentCaptor.forClass(WorkOrderAppointmentMapping.class);
        verify(mappingRepository).save(captor.capture());
        assertThat(captor.getValue().getWorkOrderId()).isEqualTo(WORKORDER_ID);
        assertThat(captor.getValue().getAppointmentId()).isEqualTo(APPOINTMENT_ID);
    }

    @Test
    @DisplayName("a workorder already linked keeps its link: every later fact repeats the same appointment")
    void leavesAnExistingLinkAlone() {
        when(mappingRepository.existsById(WORKORDER_ID)).thenReturn(true);

        assertThat(service.link(WORKORDER_ID, APPOINTMENT_ID)).isFalse();

        verify(appointmentRepository, never()).findById(any());
        verify(mappingRepository, never()).save(any());
    }

    @Test
    @DisplayName("an appointment this module does not hold is skipped, not failed")
    void skipsAnAppointmentItDoesNotHold() {
        when(mappingRepository.existsById(WORKORDER_ID)).thenReturn(false);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.empty());

        assertThat(service.link(WORKORDER_ID, APPOINTMENT_ID)).isFalse();

        verify(mappingRepository, never()).save(any());
    }
}
