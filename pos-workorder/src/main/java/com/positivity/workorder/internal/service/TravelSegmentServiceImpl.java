package com.positivity.workorder.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.domain.TravelSegmentStartedEvent;
import com.positivity.workorder.internal.domain.TravelSegmentStoppedEvent;
import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.entity.TravelSegmentAdjustment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.repository.TravelSegmentAdjustmentRepository;
import com.positivity.workorder.internal.repository.TravelSegmentRepository;
import com.positivity.workorder.service.TravelSegmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for mobile travel segment capture (Story #67).
 * Enforces: conflict detection, on-behalf validation, status lifecycle, and
 * bufferedMinutes approval-only policy.
 */
@Service
@RequiredArgsConstructor
public class TravelSegmentServiceImpl implements TravelSegmentService {
    private static final String SYSTEM = "system";

    private final Clock clock;

    private final TravelSegmentRepository travelSegmentRepository;
    private final TravelSegmentAdjustmentRepository travelSegmentAdjustmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public @NonNull TravelSegment startTravelSegment(@NonNull StartTravelSegmentRequest request) {
        // AC5: on-behalf requires a reason code
        if (request.getActedForPersonId() != null && request.getOnBehalfReasonCode() == null) {
            throw new IllegalArgumentException("onBehalfReasonCode is required when actedForPersonId is set");
        }
        // AC3: block if an active segment already exists for this assignment
        if (travelSegmentRepository.countByMobileWorkAssignmentIdAndStatus(
                        request.getMobileWorkAssignmentId(), TravelSegmentStatus.IN_PROGRESS)
                > 0) {
            throw new TravelSegmentConflictException("An active travel segment already exists for this assignment");
        }
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        TravelSegment segment = TravelSegment.builder()
                .mobileWorkAssignmentId(request.getMobileWorkAssignmentId())
                .technicianId(request.getTechnicianId())
                .segmentType(request.getSegmentType())
                .fromLocationId(request.getFromLocationId())
                .toLocationId(request.getToLocationId())
                .workOrder(request.getWorkOrderId() != null ? new Workorder(request.getWorkOrderId()) : null)
                .actedForPersonId(request.getActedForPersonId())
                .actedByUserId(request.getActedForPersonId() != null ? actor : null)
                .onBehalfReasonCode(request.getOnBehalfReasonCode())
                .startAt(Instant.now(clock))
                .status(TravelSegmentStatus.IN_PROGRESS)
                .createdBy(actor)
                .build();
        TravelSegment saved = travelSegmentRepository.save(segment);
        eventPublisher.publishEvent(
                new TravelSegmentStartedEvent(saved.getTravelSegmentId(), saved.getTechnicianId(), saved.getStartAt()));
        return saved;
    }

    @Override
    @Transactional
    public @NonNull TravelSegment stopTravelSegment(
            @NonNull UUID travelSegmentId, @NonNull StopTravelSegmentRequest request) {
        TravelSegment segment = travelSegmentRepository
                .findById(travelSegmentId)
                .orElseThrow(() -> new TravelSegmentNotFoundException(travelSegmentId));
        if (segment.getStatus() != TravelSegmentStatus.IN_PROGRESS) {
            throw new TravelSegmentConflictException("Cannot stop a segment that is not IN_PROGRESS");
        }
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        Instant endAt = Instant.now(clock);
        int minutes =
                Math.toIntExact(Duration.between(segment.getStartAt(), endAt).toMinutes());
        segment.setEndAt(endAt);
        if (request.getToLocationId() != null) {
            segment.setToLocationId(request.getToLocationId());
        }
        // rawMinutes = durationMinutes on stop; bufferedMinutes is set at approval only
        segment.setDurationMinutes(minutes);
        segment.setRawMinutes(minutes);
        segment.setStatus(TravelSegmentStatus.COMPLETED);
        segment.setLastModifiedBy(actor);
        segment.setLastModifiedAt(endAt);
        TravelSegment saved = travelSegmentRepository.save(segment);
        eventPublisher.publishEvent(new TravelSegmentStoppedEvent(
                saved.getTravelSegmentId(), saved.getTechnicianId(), saved.getEndAt(), saved.getDurationMinutes()));
        return saved;
    }

    @Override
    @Transactional
    public @NonNull List<TravelSegment> submitTravelSegments(@NonNull UUID mobileWorkAssignmentId) {
        String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        UUID technicianId;
        try {
            technicianId = UUID.fromString(username);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid technician id in security context username: " + username);
        }

        List<TravelSegment> all = new ArrayList<>(
                travelSegmentRepository
                        .findByMobileWorkAssignmentIdAndTechnicianId(mobileWorkAssignmentId, technicianId)
                        .stream()
                        .filter(s -> s.getStatus() == TravelSegmentStatus.IN_PROGRESS
                                || s.getStatus() == TravelSegmentStatus.COMPLETED)
                        .toList());
        if (all.isEmpty()) {
            throw new TravelSegmentNotFoundException(mobileWorkAssignmentId);
        }
        all.forEach(s -> s.setStatus(TravelSegmentStatus.SUBMITTED));
        travelSegmentRepository.saveAll(all);
        return all;
    }

    @Override
    @Transactional
    public @NonNull TravelSegmentAdjustment createAdjustment(
            @NonNull UUID travelSegmentId, @NonNull CreateTravelSegmentAdjustmentRequest request) {
        TravelSegment segment = travelSegmentRepository
                .findById(travelSegmentId)
                .orElseThrow(() -> new TravelSegmentNotFoundException(travelSegmentId));
        if (segment.getStatus() != TravelSegmentStatus.APPROVED) {
            throw new TravelSegmentConflictException("Adjustments can only be created for approved segments");
        }
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        TravelSegmentAdjustment adjustment = TravelSegmentAdjustment.builder()
                .travelSegment(segment)
                .adjustedStartAt(request.getAdjustedStartAt())
                .adjustedEndAt(request.getAdjustedEndAt())
                .adjustmentReason(request.getAdjustmentReason())
                .adjustedByUserId(actor)
                .build();
        return travelSegmentAdjustmentRepository.save(adjustment);
    }
}
