package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.dto.TravelSegmentAdjustmentResponse;
import com.positivity.workorder.internal.dto.TravelSegmentResponse;
import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.enums.TravelSegmentType;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.repository.TravelSegmentAdjustmentRepository;
import com.positivity.workorder.internal.repository.TravelSegmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("TravelSegmentServiceImpl Unit Tests")
class TravelSegmentServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    private static final UUID MOBILE_WORK_ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTED_FOR_PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Simulates the timestamp Spring Data auditing stamps onto @LastModifiedDate at flush time
    // (a plain save()/saveAll() on already-managed entities does not flush, so @PreUpdate/auditing
    // has not run yet when the mapper reads the entity -- see stopTravelSegment/submitTravelSegments).
    private static final Instant STALE_UPDATED_AT = Instant.parse("2023-01-01T00:00:00Z");
    private static final Instant FLUSHED_UPDATED_AT = Instant.parse("2024-06-01T12:00:00Z");

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
        var authentication = new UsernamePasswordAuthenticationToken(TECHNICIAN_ID.toString(), "N/A", List.of());
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                TECHNICIAN_ID.toString(),
                GatewaySecurityConstants.DETAIL_USER_ID,
                TECHNICIAN_ID));
        context.setAuthentication(authentication);
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
                        MOBILE_WORK_ASSIGNMENT_ID, TravelSegmentStatus.IN_PROGRESS))
                .thenReturn(0L);
        when(travelSegmentRepository.save(any(TravelSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        TravelSegmentResponse result = serviceImpl.startTravelSegment(validStartRequest());

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
        when(travelSegmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        TravelSegmentResponse result = serviceImpl.stopTravelSegment(
                SEGMENT_ID, StopTravelSegmentRequest.builder().build());

        assertThat(result.getStatus()).isEqualTo(TravelSegmentStatus.COMPLETED);
        assertThat(result.getEndAt()).isNotNull();
        assertThat(result.getDurationMinutes()).isGreaterThan(0);
        assertThat(result.getRawMinutes()).isEqualTo(result.getDurationMinutes());
        assertThat(result.getBufferedMinutes()).isNull();
    }

    @Test
    @DisplayName("Regression: stopTravelSegment returns updatedAt reflecting the post-flush auditing value")
    void stopSegment_returnsPostFlushUpdatedAt() {
        TravelSegment segment = TravelSegment.builder()
                .travelSegmentId(SEGMENT_ID)
                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                .technicianId(TECHNICIAN_ID)
                .segmentType(TravelSegmentType.DEPART_SHOP)
                .startAt(Instant.now(TEST_CLOCK).minusSeconds(600))
                .status(TravelSegmentStatus.IN_PROGRESS)
                .createdBy("system")
                .updatedAt(STALE_UPDATED_AT)
                .build();

        when(travelSegmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        // Entry starts out carrying STALE_UPDATED_AT (as an un-flushed save() would leave it);
        // only a call to saveAndFlush() bumps it to FLUSHED_UPDATED_AT, so this test fails if the
        // implementation regresses to plain save().
        when(travelSegmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            TravelSegment s = inv.getArgument(0);
            s.setUpdatedAt(FLUSHED_UPDATED_AT);
            return s;
        });

        TravelSegmentResponse result = serviceImpl.stopTravelSegment(
                SEGMENT_ID, StopTravelSegmentRequest.builder().build());

        assertThat(result.getUpdatedAt()).isEqualTo(FLUSHED_UPDATED_AT);
        verify(travelSegmentRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("AC3: startTravelSegment throws conflict when active segment exists")
    void startSegment_conflictWhenActiveExists() {
        when(travelSegmentRepository.countByMobileWorkAssignmentIdAndStatus(
                        MOBILE_WORK_ASSIGNMENT_ID, TravelSegmentStatus.IN_PROGRESS))
                .thenReturn(1L);

        assertThatThrownBy(() -> serviceImpl.startTravelSegment(validStartRequest()))
                .isInstanceOf(TravelSegmentConflictException.class);
    }

    @Test
    @DisplayName("AC4: stopTravelSegment throws not found for unknown ID")
    void stopSegment_notFoundThrows() {
        when(travelSegmentRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceImpl.stopTravelSegment(
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

        assertThatThrownBy(() -> serviceImpl.stopTravelSegment(
                        SEGMENT_ID, StopTravelSegmentRequest.builder().build()))
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
                .isInstanceOf(WorkorderRequestValidationException.class)
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
                        MOBILE_WORK_ASSIGNMENT_ID, TECHNICIAN_ID))
                .thenReturn(List.of(seg1, seg2));
        when(travelSegmentRepository.saveAllAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        serviceImpl.submitTravelSegments(MOBILE_WORK_ASSIGNMENT_ID);

        assertThat(seg1.getStatus()).isEqualTo(TravelSegmentStatus.SUBMITTED);
        assertThat(seg2.getStatus()).isEqualTo(TravelSegmentStatus.SUBMITTED);
        verify(travelSegmentRepository).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("Regression: submitTravelSegments returns updatedAt reflecting the post-flush auditing value")
    void submitSegments_returnsPostFlushUpdatedAt() {
        TravelSegment seg1 = TravelSegment.builder()
                .travelSegmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .mobileWorkAssignmentId(MOBILE_WORK_ASSIGNMENT_ID)
                .technicianId(TECHNICIAN_ID)
                .segmentType(TravelSegmentType.DEPART_SHOP)
                .startAt(Instant.now(TEST_CLOCK).minusSeconds(300))
                .status(TravelSegmentStatus.IN_PROGRESS)
                .createdBy("system")
                .updatedAt(STALE_UPDATED_AT)
                .build();

        when(travelSegmentRepository.findByMobileWorkAssignmentIdAndTechnicianId(
                        MOBILE_WORK_ASSIGNMENT_ID, TECHNICIAN_ID))
                .thenReturn(List.of(seg1));
        // Entry starts out carrying STALE_UPDATED_AT (as an un-flushed saveAll() would leave it);
        // only a call to saveAllAndFlush() bumps it to FLUSHED_UPDATED_AT, so this test fails if
        // the implementation regresses to plain saveAll().
        when(travelSegmentRepository.saveAllAndFlush(any())).thenAnswer(inv -> {
            List<TravelSegment> segments = inv.getArgument(0);
            segments.forEach(s -> s.setUpdatedAt(FLUSHED_UPDATED_AT));
            return segments;
        });

        List<TravelSegmentResponse> result = serviceImpl.submitTravelSegments(MOBILE_WORK_ASSIGNMENT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUpdatedAt()).isEqualTo(FLUSHED_UPDATED_AT);
        verify(travelSegmentRepository).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("AC6-empty: submitTravelSegments throws not found when no segments exist")
    void submitSegments_emptyListThrowsNotFound() {
        when(travelSegmentRepository.findByMobileWorkAssignmentIdAndTechnicianId(
                        MOBILE_WORK_ASSIGNMENT_ID, TECHNICIAN_ID))
                .thenReturn(List.of());

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

        TravelSegmentAdjustmentResponse result = serviceImpl.createAdjustment(SEGMENT_ID, validAdjustmentRequest());

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
