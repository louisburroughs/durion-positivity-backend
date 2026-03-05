package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.entity.TravelSegmentAdjustment;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.enums.TravelSegmentType;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.repository.TravelSegmentAdjustmentRepository;
import com.positivity.workorder.internal.repository.TravelSegmentRepository;
import com.positivity.workorder.internal.service.TravelSegmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TravelSegmentServiceImpl Unit Tests")
class TravelSegmentServiceImplTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        private static final UUID MOBILE_WORK_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID TECHNICIAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID SEGMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID ACTED_FOR_PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

        @Mock
        private TravelSegmentRepository travelSegmentRepository;

        @Mock
        private TravelSegmentAdjustmentRepository travelSegmentAdjustmentRepository;

        @Mock
        private ApplicationEventPublisher eventPublisher;

        @InjectMocks
        private TravelSegmentServiceImpl serviceImpl;

        @BeforeEach
        void setUpSecurityContext() {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                                TECHNICIAN_ID.toString(),
                                "N/A",
                                List.of()));
                SecurityContextHolder.setContext(context);
        }

        @AfterEach
        void clearSecurityContext() {
                SecurityContextHolder.clearContext();
        }

        private StartTravelSegmentRequest validStartRequest() {
                return StartTravelSegmentRequest.builder()
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .build();
        }

        private CreateTravelSegmentAdjustmentRequest validAdjustmentRequest() {
                return CreateTravelSegmentAdjustmentRequest.builder()
                                .adjustmentReason("Correcting times")
                                .build();
        }

        @Test
        @DisplayName("AC1: startTravelSegment creates IN_PROGRESS segment")
        void startSegment_createsInProgressSegment() {
                when(travelSegmentRepository.countByMobileWorkAssignmentIdAndStatus(
                                MOBILE_WORK_ASSIGNMENT_ID, TravelSegmentStatus.IN_PROGRESS)).thenReturn(0L);
                when(travelSegmentRepository.save(any(TravelSegment.class))).thenAnswer(inv -> inv.getArgument(0));

                TravelSegment result = serviceImpl.startTravelSegment(validStartRequest());

                assertThat(result.getStatus()).isEqualTo(TravelSegmentStatus.IN_PROGRESS);
                assertThat(result.getStartAt()).isNotNull();
                assertThat(result.getCreatedBy()).isNotNull();
                verify(travelSegmentRepository).save(any(TravelSegment.class));
        }

        @Test
        @DisplayName("AC2: stopTravelSegment completes segment with duration")
        void stopSegment_completesSegment() {
                TravelSegment segment = TravelSegment.builder()
                                .travelSegmentId(SEGMENT_ID)
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(600))
                                .status(TravelSegmentStatus.IN_PROGRESS)
                                .createdBy("system")
                                .build();

                when(travelSegmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
                when(travelSegmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                TravelSegment result = serviceImpl.stopTravelSegment(SEGMENT_ID,
                                StopTravelSegmentRequest.builder().build());

                assertThat(result.getStatus()).isEqualTo(TravelSegmentStatus.COMPLETED);
                assertThat(result.getEndAt()).isNotNull();
                assertThat(result.getDurationMinutes()).isGreaterThan(0);
                assertThat(result.getRawMinutes()).isEqualTo(result.getDurationMinutes());
                assertThat(result.getBufferedMinutes()).isNull();
        }

        @Test
        @DisplayName("AC3: startTravelSegment throws conflict when active segment exists")
        void startSegment_conflictWhenActiveExists() {
                when(travelSegmentRepository.countByMobileWorkAssignmentIdAndStatus(
                                MOBILE_WORK_ASSIGNMENT_ID, TravelSegmentStatus.IN_PROGRESS)).thenReturn(1L);

                assertThatThrownBy(() -> serviceImpl.startTravelSegment(validStartRequest()))
                                .isInstanceOf(TravelSegmentConflictException.class);
        }

        @Test
        @DisplayName("AC4: stopTravelSegment throws not found for unknown ID")
        void stopSegment_notFoundThrows() {
                when(travelSegmentRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

                assertThatThrownBy(
                                () -> serviceImpl.stopTravelSegment(
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                StopTravelSegmentRequest.builder().build()))
                                .isInstanceOf(TravelSegmentNotFoundException.class);
        }

        @Test
        @DisplayName("AC4-guard: stopTravelSegment throws conflict when segment is not IN_PROGRESS")
        void stopSegment_wrongStatusThrows() {
                TravelSegment completedSegment = TravelSegment.builder()
                                .travelSegmentId(SEGMENT_ID)
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(600))
                                .status(TravelSegmentStatus.COMPLETED)
                                .createdBy("system")
                                .build();

                when(travelSegmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(completedSegment));

                assertThatThrownBy(() -> serviceImpl.stopTravelSegment(SEGMENT_ID,
                                StopTravelSegmentRequest.builder().build()))
                                .isInstanceOf(TravelSegmentConflictException.class)
                                .hasMessageContaining("Cannot stop a segment that is not IN_PROGRESS");
        }

        @Test
        @DisplayName("AC5: startTravelSegment throws when actedForPersonId set without onBehalfReasonCode")
        void startSegment_onBehalfWithoutReasonThrows() {
                StartTravelSegmentRequest req = StartTravelSegmentRequest.builder()
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .actedForPersonId(ACTED_FOR_PERSON_ID)
                                .onBehalfReasonCode(null)
                                .build();

                assertThatThrownBy(() -> serviceImpl.startTravelSegment(req))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("onBehalfReasonCode is required");
        }

        @Test
        @DisplayName("AC6: submitTravelSegments changes status to SUBMITTED")
        void submitSegments_changesStatusToSubmitted() {
                TravelSegment seg1 = TravelSegment.builder()
                                .travelSegmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(300))
                                .status(TravelSegmentStatus.IN_PROGRESS)
                                .createdBy("system")
                                .build();
                TravelSegment seg2 = TravelSegment.builder()
                                .travelSegmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.ARRIVE_CUSTOMER_SITE)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(600))
                                .endAt(Instant.now(TEST_CLOCK).minusSeconds(300))
                                .status(TravelSegmentStatus.COMPLETED)
                                .createdBy("system")
                                .build();

                when(travelSegmentRepository.findByMobileWorkAssignmentIdAndTechnicianId(
                                MOBILE_WORK_ASSIGNMENT_ID, TECHNICIAN_ID)).thenReturn(List.of(seg1, seg2));
                when(travelSegmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

                serviceImpl.submitTravelSegments(MOBILE_WORK_ASSIGNMENT_ID);

                assertThat(seg1.getStatus()).isEqualTo(TravelSegmentStatus.SUBMITTED);
                assertThat(seg2.getStatus()).isEqualTo(TravelSegmentStatus.SUBMITTED);
                verify(travelSegmentRepository).saveAll(any());
        }

        @Test
        @DisplayName("AC6-empty: submitTravelSegments throws not found when no segments exist")
        void submitSegments_emptyListThrowsNotFound() {
                when(travelSegmentRepository.findByMobileWorkAssignmentIdAndTechnicianId(
                                MOBILE_WORK_ASSIGNMENT_ID, TECHNICIAN_ID)).thenReturn(List.of());

                assertThatThrownBy(() -> serviceImpl.submitTravelSegments(MOBILE_WORK_ASSIGNMENT_ID))
                                .isInstanceOf(TravelSegmentNotFoundException.class);
        }

        @Test
        @DisplayName("AC7: createAdjustment succeeds for APPROVED segment")
        void createAdjustment_approvedSegmentSucceeds() {
                TravelSegment segment = TravelSegment.builder()
                                .travelSegmentId(SEGMENT_ID)
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(600))
                                .status(TravelSegmentStatus.APPROVED)
                                .createdBy("system")
                                .build();

                when(travelSegmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
                when(travelSegmentAdjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                TravelSegmentAdjustment result = serviceImpl.createAdjustment(SEGMENT_ID, validAdjustmentRequest());

                assertThat(result.getApprovalStatus()).isEqualTo("PENDING");
                assertThat(result.getAdjustedByUserId()).isNotNull();
        }

        @Test
        @DisplayName("AC8: createAdjustment throws for DRAFT segment")
        void createAdjustment_draftSegmentThrows() {
                TravelSegment draftSegment = TravelSegment.builder()
                                .travelSegmentId(SEGMENT_ID)
                                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                                .technicianId(TECHNICIAN_ID)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(600))
                                .status(TravelSegmentStatus.DRAFT)
                                .createdBy("system")
                                .build();

                when(travelSegmentRepository.findById(any())).thenReturn(Optional.of(draftSegment));

                assertThatThrownBy(() -> serviceImpl.createAdjustment(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"), validAdjustmentRequest()))
                                .isInstanceOf(TravelSegmentConflictException.class)
                                .hasMessageContaining("Adjustments can only be created for approved segments");
        }
}
