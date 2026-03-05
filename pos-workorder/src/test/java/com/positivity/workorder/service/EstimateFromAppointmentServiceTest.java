package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.client.DocumentClient;
import com.positivity.workorder.internal.client.PeopleLocationClient;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.EstimateServiceImpl;

/**
 * Unit tests for CAP-140 Story #65: Create Draft Estimate from Appointment.
 *
 * Covers AC1–AC5 at the service layer: new estimate creation, idempotent
 * existing-estimate returns, DRAFT status defaults, and required-field
 * validation for appointment and customer identifiers.
 *
 * Issue: CAP-140
 */
@ExtendWith(MockitoExtension.class)
class EstimateFromAppointmentServiceTest {

        @Mock
        private EstimateRepository estimateRepository;

        @Mock
        private EstimateItemRepository estimateItemRepository;

        @Mock
        private EstimateSnapshotRepository estimateSnapshotRepository;

        @Mock
        private ApprovalConfigurationRepository approvalConfigurationRepository;

        @Mock
        private WorkorderRepository workOrderRepository;

        @Mock
        private ApplicationEventPublisher eventPublisher;

        @Mock
        private BillingRulesClientService billingRulesClientService;

        @Mock
        private TaxClient taxClient;

        @Mock
        private PeopleLocationClient peopleLocationClient;

        @Mock
        private DocumentClient documentClient;

        @Mock
        private ObjectMapper objectMapper;

        @InjectMocks
        private EstimateServiceImpl estimateService;

        private static final UUID APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
        private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
        private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
        private static final UUID IDEMPOTENCY_KEY = UUID.fromString("00000000-0000-0000-0000-000000000005");

        private CreateEstimateFromAppointmentRequest validRequest() {
                return CreateEstimateFromAppointmentRequest.builder()
                                .idempotencyKey(IDEMPOTENCY_KEY)
                                .appointmentId(APPOINTMENT_ID)
                                .customerId(CUSTOMER_ID)
                                .vehicleId(VEHICLE_ID)
                                .locationId(LOCATION_ID)
                                .build();
        }

        // Issue CAP-140 start: AC1 — new estimate is created with DRAFT status and
        // estimateId returned
        @Test
        void createEstimateFromAppointment_whenNoExistingEstimate_createsAndReturnsNewEstimate() {
                // Given
                UUID expectedId = UUID.fromString("b8a0c0b0-0c0a-0b0a-0c0a-0b0a0c0a0b0a");
                Estimate savedEstimate = Estimate.builder()
                                .id(expectedId)
                                .status(EstimateStatus.DRAFT)
                                .appointmentId(APPOINTMENT_ID)
                                .customerId(CUSTOMER_ID)
                                .vehicleId(VEHICLE_ID)
                                .locationId(LOCATION_ID)
                                .build();
                when(estimateRepository.findByAppointmentId(APPOINTMENT_ID)).thenReturn(Optional.empty());
                when(estimateRepository.save(any(Estimate.class))).thenReturn(savedEstimate);

                // When
                CreateEstimateFromAppointmentResponse response = estimateService
                                .createEstimateFromAppointment(validRequest());

                // Then
                assertThat(response).isNotNull();
                assertThat(response.getEstimateId()).isNotNull();
                verify(estimateRepository).save(any(Estimate.class));
        }
        // Issue CAP-140 end: AC1

        // Issue CAP-140 start: AC2 — idempotent request returns existing estimateId
        @Test
        void createEstimateFromAppointment_whenEstimateExistsForAppointment_returnsExistingEstimateId() {
                // Given
                UUID existingEstimateId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
                Estimate existingEstimate = Estimate.builder()
                                .id(existingEstimateId)
                                .status(EstimateStatus.DRAFT)
                                .appointmentId(APPOINTMENT_ID)
                                .build();
                when(estimateRepository.findByAppointmentId(APPOINTMENT_ID)).thenReturn(Optional.of(existingEstimate));

                // When
                CreateEstimateFromAppointmentResponse response = estimateService
                                .createEstimateFromAppointment(validRequest());

                // Then
                assertThat(response).isNotNull();
                assertThat(response.getEstimateId()).isEqualTo(existingEstimateId);
                verify(estimateRepository, never()).save(any(Estimate.class));
        }
        // Issue CAP-140 end: AC2

        // Issue CAP-140 start: AC3 — new estimate defaults to DRAFT status
        @Test
        void createEstimateFromAppointment_setsStatusToDraft() {
                // Given
                Estimate savedEstimate = Estimate.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.DRAFT)
                                .appointmentId(APPOINTMENT_ID)
                                .build();
                when(estimateRepository.findByAppointmentId(APPOINTMENT_ID)).thenReturn(Optional.empty());
                when(estimateRepository.save(any(Estimate.class))).thenReturn(savedEstimate);

                // When
                CreateEstimateFromAppointmentResponse response = estimateService
                                .createEstimateFromAppointment(validRequest());

                // Then
                assertThat(response.getStatus()).isEqualTo("DRAFT");
                verify(estimateRepository).save(argThat(estimate -> estimate.getStatus() == EstimateStatus.DRAFT));
        }
        // Issue CAP-140 end: AC3

        // Issue CAP-140 start: AC4 — appointmentId is required
        @Test
        void createEstimateFromAppointment_whenAppointmentIdIsNull_throwsException() {
                // Given
                CreateEstimateFromAppointmentRequest request = CreateEstimateFromAppointmentRequest.builder()
                                .idempotencyKey(IDEMPOTENCY_KEY)
                                .appointmentId(null)
                                .customerId(CUSTOMER_ID)
                                .vehicleId(VEHICLE_ID)
                                .locationId(LOCATION_ID)
                                .build();

                // When & Then
                assertThatThrownBy(() -> estimateService.createEstimateFromAppointment(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("appointmentId is required");
        }
        // Issue CAP-140 end: AC4

        // Issue CAP-140 start: AC5 — customerId is required
        @Test
        void createEstimateFromAppointment_whenCustomerIdIsNull_throwsException() {
                // Given
                CreateEstimateFromAppointmentRequest request = CreateEstimateFromAppointmentRequest.builder()
                                .idempotencyKey(IDEMPOTENCY_KEY)
                                .appointmentId(APPOINTMENT_ID)
                                .customerId(null)
                                .vehicleId(VEHICLE_ID)
                                .locationId(LOCATION_ID)
                                .build();

                // When & Then
                assertThatThrownBy(() -> estimateService.createEstimateFromAppointment(request))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("customerId is required");
        }
        // Issue CAP-140 end: AC5
}
